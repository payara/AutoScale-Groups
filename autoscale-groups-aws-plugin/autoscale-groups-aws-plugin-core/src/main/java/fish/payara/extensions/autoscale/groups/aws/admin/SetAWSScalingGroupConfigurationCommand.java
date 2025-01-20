package fish.payara.extensions.autoscale.groups.aws.admin;

import com.sun.enterprise.config.serverbeans.Nodes;
import com.sun.enterprise.util.StringUtils;
import fish.payara.extensions.autoscale.groups.admin.SetScalingGroupConfigurationCommand;
import fish.payara.extensions.autoscale.groups.aws.AWSScalingGroup;
import jakarta.inject.Inject;
import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RestParam;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.TransactionFailure;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.util.List;

/**
 * Sets the configuration of a {@link AWSScalingGroup}.
 */
@Service(name = "set-aws-scaling-group-configuration")
@PerLookup
@ExecuteOn(RuntimeType.DAS)
@RestEndpoints({
        @RestEndpoint(configBean = AWSScalingGroup.class,
                opType = RestEndpoint.OpType.POST,
                path = "set-aws-scaling-group-configuration",
                description = "Sets the configuration of the target Scaling Group",
                params = {
                        @RestParam(name = "name", value = "$parent")
                }
        )
})
public class SetAWSScalingGroupConfigurationCommand extends SetScalingGroupConfigurationCommand {

    @Param(name = "region")
    private String region;

    @Param(name = "instance-type")
    private String instanceType;

    @Param(name = "ami-id")
    private String amiId;

    @Param(name = "security-group")
    private String securityGroup;

    @Param(name = "min-instances")
    private int minInstances;

    @Param(name = "max-instances")
    private int maxInstances;

    @Param(name = "payara-install-dir")
    private String payaraInstallDir;

    @Param(name = "password-file-path")
    private String passwordFilePath;

    @Inject
    protected Nodes nodes;

    @Override
    public void execute(AdminCommandContext adminCommandContext) {
        try {
            validateParams();
        } catch (CommandValidationException commandValidationException) {
            adminCommandContext.getActionReport().setFailureCause(commandValidationException);
            adminCommandContext.getActionReport().setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        try {
            ConfigSupport.apply(scalingGroupsProxy -> {
                ConfigSupport.apply(awsScalingGroupProxy -> {
                    if (StringUtils.ok(deploymentGroupRef)) {
                        awsScalingGroupProxy.setDeploymentGroupRef(deploymentGroupRef);
                    }

                    if (StringUtils.ok(configRef)) {
                        awsScalingGroupProxy.setConfigRef(configRef);
                    }

                    if (StringUtils.ok(region)) {
                        awsScalingGroupProxy.setRegion(region);
                    }

                    if (StringUtils.ok(instanceType)) {
                        awsScalingGroupProxy.setInstanceType(instanceType);
                    }

                    if (StringUtils.ok(amiId)) {
                        awsScalingGroupProxy.setAmiId(amiId);
                    }

                    if (StringUtils.ok(securityGroup)) {
                        awsScalingGroupProxy.setSecurityGroup(securityGroup);
                    }

                    if (minInstances > 0) {
                        awsScalingGroupProxy.setMinInstances(minInstances);
                    }

                    if (maxInstances > 0) {
                        awsScalingGroupProxy.setMaxInstances(maxInstances);
                    }

                    if (StringUtils.ok(payaraInstallDir)) {
                        awsScalingGroupProxy.setPayaraInstallDir(payaraInstallDir);
                    }

                    if (StringUtils.ok(passwordFilePath)) {
                        awsScalingGroupProxy.setPasswordFilePath(passwordFilePath);
                    }



                    return awsScalingGroupProxy;
                }, (AWSScalingGroup) scalingGroupsProxy.getScalingGroup(name));

                return scalingGroupsProxy;
            }, scalingGroups);
        } catch (TransactionFailure transactionFailure) {
            adminCommandContext.getActionReport().setActionExitCode(ActionReport.ExitCode.FAILURE);
            adminCommandContext.getActionReport().setFailureCause(transactionFailure);
        }
    }

    @Override
    protected void validateParams() throws CommandValidationException {
        super.validateParams();

        List<AWSScalingGroup> awsScalingGroups = scalingGroups.getScalingGroupsOfType(AWSScalingGroup.class);

        if (awsScalingGroups.isEmpty()) {
            throw new CommandValidationException("Scaling Group " + name + " is not a AWS Scaling Group.");
        }

        boolean exists = false;
        for (AWSScalingGroup awsScalingGroup : awsScalingGroups) {
            if (awsScalingGroup.getName().equals(name)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            throw new CommandValidationException("Scaling Group " + name + " is not a AWS Scaling Group.");
        }

        if (minInstances < 0) {
            throw new CommandValidationException("Min instances must be greater than zero");
        }

        if (maxInstances < minInstances) {
            throw new CommandValidationException("Max instances must be greater than Min instances");
        }


        Region r = Region.of(region);
        if (!Region.regions().contains(r)) {
            throw new CommandValidationException("Unknown AWS Region");
        }
        InstanceType instanceType1 = InstanceType.fromValue(instanceType);
        if (instanceType1 == null) {
            throw new CommandValidationException("Unknown InstanceType");
        }

        if (amiId.isBlank() || !amiId.startsWith("ami-")) {
            throw new CommandValidationException("Invalid AWS AMI ID");
        }

        if (securityGroup.isBlank() || !securityGroup.startsWith("sg-")) {
            throw new CommandValidationException("Invalid AWS Security Group");
        }

        if (payaraInstallDir.isBlank()) {
            throw new CommandValidationException("Payara Install directory cannot be blank");
        }

        if (passwordFilePath.isBlank()) {
            throw new CommandValidationException("Password file path cannot be blank");
        }
    }
}
