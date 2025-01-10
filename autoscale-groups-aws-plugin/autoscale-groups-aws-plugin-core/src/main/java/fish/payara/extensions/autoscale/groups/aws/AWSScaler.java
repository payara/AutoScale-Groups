package fish.payara.extensions.autoscale.groups.aws;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.Servers;
import fish.payara.enterprise.config.serverbeans.DeploymentGroup;
import fish.payara.extensions.autoscale.groups.Scaler;
import fish.payara.extensions.autoscale.groups.ScalerFor;
import fish.payara.extensions.autoscale.groups.ScalingGroup;
import fish.payara.extensions.autoscale.groups.core.admin.ScaleCommandHelper;
import jakarta.inject.Inject;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.internal.api.InternalSystemAdministrator;
import org.jvnet.hk2.annotations.Service;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2AsyncClient;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@ScalerFor(AWSScalingGroup.class)
public class AWSScaler extends Scaler {

    @Inject
    private CommandRunner commandRunner;

    @Inject
    Servers servers;

    @Inject
    InternalSystemAdministrator internalSystemAdministrator;


    private static Ec2AsyncClient ec2AsyncClient;
    private static String cachedRegion;
    private static final List<String> ec2InstanceIds = new ArrayList<>();


    @Override
    protected void validate(int numberOfInstances, ScalingGroup scalingGroup) throws CommandValidationException {
        super.validate(numberOfInstances, scalingGroup);
        AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
        if (awsScalingGroup.getMinInstances() < 0) {
            throw new CommandValidationException("Min instances must be greater than zero");
        }

        if (awsScalingGroup.getMaxInstances() < awsScalingGroup.getMinInstances()) {
            throw new CommandValidationException("Max instances must be greater than Min instances");
        }


        Region region = Region.of(awsScalingGroup.getRegion());
        if (!Region.regions().contains(region)) {
            throw new CommandValidationException("Unknown AWS Region");
        }
        InstanceType instanceType = InstanceType.fromValue(awsScalingGroup.getInstanceType());
        if (instanceType == null) {
            throw new CommandValidationException("Unknown InstanceType");
        }

        if (awsScalingGroup.getAmiId().isBlank() || !awsScalingGroup.getAmiId().startsWith("ami-")) {
            throw new CommandValidationException("Invalid AWS AMI ID");
        }

        if (awsScalingGroup.getSecurityGroup().isBlank() || !awsScalingGroup.getSecurityGroup().startsWith("sg-")) {
            throw new CommandValidationException("Invalid AWS Security Group");
        }

        if (awsScalingGroup.getPayaraInstallDir().isBlank()) {
            throw new CommandValidationException("Payara Install directory cannot be blank");
        }

        if (awsScalingGroup.getPasswordFilePath().isBlank()) {
            throw new CommandValidationException("Password file path cannot be blank");
        }

        if (internalSystemAdministrator == null) {
            internalSystemAdministrator = serviceLocator.getService(InternalSystemAdministrator.class);

            if (internalSystemAdministrator == null) {
                throw new CommandValidationException(
                        "Could not find or initialise InternalSystemAdministrator to execute commands with!");
            }
        }
    }

    @Override
    public ActionReport scaleUp(int numberOfNewInstances, ScalingGroup scalingGroup) {
        ActionReport actionReport = commandRunner.getActionReport("plain");
        try {
            validate(numberOfNewInstances, scalingGroup);
            AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
            if (deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef()).getInstances().size() + numberOfNewInstances > awsScalingGroup.getMaxInstances()) {
                throw new CommandValidationException("Cannot have more than " + awsScalingGroup.getMaxInstances() + " instances");
            }
        } catch (CommandValidationException commandValidationException) {
            actionReport.setMessage("Scale up operation cancelled: an error was encountered during validation");
            actionReport.setFailureCause(commandValidationException);
            actionReport.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return actionReport;
        }
        createEC2Instance(numberOfNewInstances, scalingGroup, actionReport);
        return actionReport;
    }

    @Override
    public ActionReport scaleDown(int numberOfInstancesToRemove, ScalingGroup scalingGroup) {
        ActionReport actionReport = commandRunner.getActionReport("plain");
        try {
            validate(numberOfInstancesToRemove, scalingGroup);
            AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
            if (deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef()).getInstances().size() - numberOfInstancesToRemove < awsScalingGroup.getMinInstances()) {
                throw new CommandValidationException("Cannot have less than " + awsScalingGroup.getMinInstances() + " instances");
            }
        } catch (CommandValidationException commandValidationException) {
            actionReport.setMessage("Scale down operation cancelled: an error was encountered during validation");
            actionReport.setFailureCause(commandValidationException);
            actionReport.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return actionReport;
        }

        // Determine which instances to stop, attempting to keep the nodes balanced
        List<String> instanceNames = determineInstancesToStop(numberOfInstancesToRemove, scalingGroup);
        // Stop the instances in parallel
        stopInstances(instanceNames, actionReport.addSubActionsReport());
        // Delete the instances sequentially
        deleteInstances(instanceNames, actionReport.addSubActionsReport(), scalingGroup);

        return actionReport;
    }

    /**
     * Deletes instances sequentially.
     *
     * @param instanceNames The names of the instances to stop.
     * @param actionReport The action report to add the command outputs to.
     */
    private void deleteInstances(List<String> instanceNames, ActionReport actionReport, ScalingGroup scalingGroup) {
        for (String instanceName : instanceNames) {
            CommandRunner.CommandInvocation deleteInstanceCommand = commandRunner.getCommandInvocation(
                    "delete-instance", actionReport.addSubActionsReport(), internalSystemAdministrator.getSubject());

            ParameterMap parameterMap = new ParameterMap();
            // Primary parameter is called DEFAULT, regardless of its actual name
            parameterMap.add("DEFAULT", instanceName);

            deleteInstanceCommand.parameters(parameterMap);
            deleteInstanceCommand.execute();

            if (ec2InstanceIds.contains(instanceName)) {
                terminateEC2Async(instanceName, scalingGroup).join();
            }
        }
    }

    private CompletableFuture<Object> terminateEC2Async(String instanceId, ScalingGroup scalingGroup) {
        AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build();

        CompletableFuture<TerminateInstancesResponse> responseFuture = getAsyncClient(awsScalingGroup.getRegion()).terminateInstances(terminateRequest);
        return responseFuture.thenCompose(terminateResponse -> {
            if (terminateResponse == null) {
                throw new RuntimeException("No response received for terminating instance " + instanceId);
            }
            return getAsyncClient(awsScalingGroup.getRegion()).waiter()
                    .waitUntilInstanceTerminated(r -> r.instanceIds(instanceId))
                    .thenApply(waiterResponse -> null);
        }).exceptionally(throwable -> {
            // Handle any exceptions that occurred during the async call
            throw new RuntimeException("Failed to terminate EC2 instance: " + throwable.getMessage(), throwable);
        });
    }

    /**
     * Stops the instances in parallel using {@link ScaleCommandHelper}.
     *
     * @param instanceNames The names of the instances to stop
     * @param actionReport The action report to add the command outputs to
     */
    private void stopInstances(List<String> instanceNames, ActionReport actionReport) {
        ScaleCommandHelper scaleCommandHelper = new ScaleCommandHelper(serviceLocator.getService(Domain.class),
                commandRunner, internalSystemAdministrator.getSubject());
        scaleCommandHelper.runCommandInParallelAcrossInstances("stop-instance", new ParameterMap(),
                instanceNames, actionReport.addSubActionsReport());
    }

    /**
     * Determines the names of which instances to stop in the scaling group, attempting to keep the number of instances
     * on nodes balanced.
     *
     * @param numberOfInstancesToRemove The number of instances to remove
     * @param scalingGroup              The scaling group to remove the instances from
     * @return A list of names of the instances to stop
     */
    private List<String> determineInstancesToStop(int numberOfInstancesToRemove, ScalingGroup scalingGroup) {
        List<String> instanceNames = new ArrayList<>();

        // Quick check: will we just be removing all instances? If so we can skip trying to figure out the balance
        boolean removeAll = false;
        DeploymentGroup deploymentGroup = deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef());
        List<Server> instances = deploymentGroup.getInstances();

        if (instances.size() <= numberOfInstancesToRemove) {
            removeAll = true;
        }

        if (removeAll) {
            for (Server server : instances) {
                instanceNames.add(server.getName());
            }
            return instanceNames;
        }

        // Loop until we've removed the requested number of instances
        int instanceCounter = numberOfInstancesToRemove;
        for (Server server : instances) {
            if (instanceCounter <= 0) {
                break;
            }
            if (!instanceNames.contains(server.getName())) {
                instanceNames.add(server.getName());
                instanceCounter--;
            }
        }

        return instanceNames;
    }

    /**
     * Creates the requested number of instances using the {@link AWSScalingGroup scaling group} config
     *
     * @param numberOfNewInstances The number of instances to create
     * @param scalingGroup         The scaling group we're creating the instances against
     * @param actionReport         The action report we want to add out command outputs to
     */
    private void createEC2Instance(int numberOfNewInstances, ScalingGroup scalingGroup, ActionReport actionReport) {
        AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
        RunInstancesRequest runInstancesRequest = RunInstancesRequest.builder()
                .instanceType(awsScalingGroup.getInstanceType())
                .maxCount(numberOfNewInstances)
                .minCount(numberOfNewInstances)
                .imageId(awsScalingGroup.getAmiId())
                .securityGroupIds(awsScalingGroup.getSecurityGroup())
                .userData(getUserData(awsScalingGroup))
                .build();

        CompletableFuture<RunInstancesResponse> responseFuture = getAsyncClient(awsScalingGroup.getRegion()).runInstances(runInstancesRequest);
        CompletableFuture<List<String>> future = responseFuture.thenCompose(response -> {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (Instance instance : response.instances()) {
                String instanceIdVal = instance.instanceId();
                futures.add(getAsyncClient(awsScalingGroup.getRegion()).waiter()
                        .waitUntilInstanceExists(r -> r.instanceIds(instanceIdVal))
                        .thenCompose(waitResponse -> getAsyncClient(awsScalingGroup.getRegion()).waiter()
                                .waitUntilInstanceRunning(r -> r.instanceIds(instanceIdVal))
                                .thenApply(runningResponse -> instanceIdVal)));
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> response.instances().stream().map(Instance::instanceId).collect(Collectors.toList()));
        }).exceptionally(throwable -> {
            actionReport.setMessage("Scale up operation cancelled: an error was encountered during instance creation");
            actionReport.setFailureCause(throwable);
            actionReport.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return null;
        });
        ec2InstanceIds.addAll(future.join());
    }

    private static Ec2AsyncClient getAsyncClient(String region) {
        if (ec2AsyncClient == null || !region.equals(cachedRegion)) {
            /*
            The `NettyNioAsyncHttpClient` class is part of the AWS SDK for Java, version 2,
            and it is designed to provide a high-performance, asynchronous HTTP client for interacting with AWS services.
             It uses the Netty framework to handle the underlying network communication and the Java NIO API to
             provide a non-blocking, event-driven approach to HTTP requests and responses.
             */
            SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(50)  // Adjust as needed.
                    .connectionTimeout(Duration.ofSeconds(60))  // Set the connection timeout.
                    .readTimeout(Duration.ofSeconds(60))  // Set the read timeout.
                    .writeTimeout(Duration.ofSeconds(60))  // Set the write timeout.
                    .build();

            ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofMinutes(2))  // Set the overall API call timeout.
                    .apiCallAttemptTimeout(Duration.ofSeconds(90))  // Set the individual call attempt timeout.
                    .build();

            ec2AsyncClient = Ec2AsyncClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .overrideConfiguration(overrideConfig)
                    .build();
            cachedRegion = region;
        }
        return ec2AsyncClient;
    }

    private String getUserData(AWSScalingGroup scalingGroup) {

        String dasHost = "";
        String dasPort = "";
        for (Server server : servers.getServer()) {
            if (server.isDas()) {
                dasHost = server.getAdminHost();
                dasPort = Integer.toString(server.getAdminPort());
                break;
            }
        }

        String userData = "#!/bin/bash\n" +
                "DAS_HOST=" +
                dasHost +
                "\n" +
                "DAS_PORT=" +
                dasPort +
                "\n" +
                "DEPLOYMENT_GROUP=" +
                scalingGroup.getDeploymentGroupRef() +
                "\n" +
                "PAYARA_DIR=" +
                scalingGroup.getPayaraInstallDir() +
                "\n" +
                "PASSWORD_FILE=" +
                scalingGroup.getPasswordFilePath() +
                "\n" +
                "ec2InstanceId=$(ec2-metadata --instance-id | cut -d \" \" -f 2);\n" +
                "ASADMIN=\"${PAYARA_DIR}/bin/asadmin\"\n" +
                "HOSTNAME=$(hostname -I | cut -f1 -d ' ')\n" +
                "NODE_NAME=$(${ASADMIN} -I false -T -a -s -H $DAS_HOST -p $DAS_PORT -W $PASSWORD_FILE _create-node-temp --nodehost $HOSTNAME)\n" +
                "${ASADMIN} -I false -T -H $DAS_HOST -p $DAS_PORT -W $PASSWORD_FILE create-local-instance --node $NODE_NAME --ip $HOSTNAME --dockernode true --deploymentgroup $DEPLOYMENT_GROUP $ec2InstanceId\n" +
                "${ASADMIN} -H $DAS_HOST -p $DAS_PORT -W $PASSWORD_FILE start-local-instance --node $NODE_NAME $INSTANCE_NAME";

        return Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
    }
}
