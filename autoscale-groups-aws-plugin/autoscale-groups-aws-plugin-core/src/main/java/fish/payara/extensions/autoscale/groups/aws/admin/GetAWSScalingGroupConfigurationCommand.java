package fish.payara.extensions.autoscale.groups.aws.admin;

import fish.payara.extensions.autoscale.groups.admin.GetScalingGroupConfigurationCommand;
import fish.payara.extensions.autoscale.groups.aws.AWSScalingGroup;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RestParam;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.hk2.api.PerLookup;
import org.jvnet.hk2.annotations.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Gets the configuration of a {@link AWSScalingGroup}.
 */
@Service(name = "get-aws-scaling-group-configuration")
@PerLookup
@ExecuteOn(RuntimeType.DAS)
@RestEndpoints({
        @RestEndpoint(configBean = AWSScalingGroup.class,
                opType = RestEndpoint.OpType.GET,
                path = "get-aws-scaling-group-configuration",
                description = "Gets the configuration of the target Scaling Group",
                params = {
                        @RestParam(name = "id", value = "$parent")
                }
        )
})
public class GetAWSScalingGroupConfigurationCommand extends GetScalingGroupConfigurationCommand {

    @Override
    public void execute(AdminCommandContext adminCommandContext) {
        try {
            validateParams();
        } catch (CommandValidationException commandValidationException) {
            adminCommandContext.getActionReport().setFailureCause(commandValidationException);
            adminCommandContext.getActionReport().setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        AWSScalingGroup awsScalingGroup = null;

        // Search through the scaling groups, checking for the one with a reference to our requested deployment group
        for (AWSScalingGroup awsScalingGroupIterator : scalingGroups.getScalingGroupsOfType(AWSScalingGroup.class)) {
            if (awsScalingGroupIterator.getName().equals(name)) {
                awsScalingGroup = awsScalingGroupIterator;
                break;
            }
        }

        adminCommandContext.getActionReport().setMessage("AWS Scaling Group: " + awsScalingGroup.getName());
        adminCommandContext.getActionReport().appendMessage("\nConfig Ref: " + awsScalingGroup.getConfigRef());
        adminCommandContext.getActionReport().appendMessage("\nDeployment Group Ref: " + awsScalingGroup.getDeploymentGroupRef());
        adminCommandContext.getActionReport().appendMessage("\nRegion: " +  awsScalingGroup.getRegion());
        adminCommandContext.getActionReport().appendMessage("\nInstance Type: " +  awsScalingGroup.getInstanceType());
        adminCommandContext.getActionReport().appendMessage("\nAMI Id: " +  awsScalingGroup.getAmiId());
        adminCommandContext.getActionReport().appendMessage("\nSecurity Group: " +  awsScalingGroup.getSecurityGroup());
        adminCommandContext.getActionReport().appendMessage("\nMinimum Instances: " +  awsScalingGroup.getMinInstances());
        adminCommandContext.getActionReport().appendMessage("\nMaximum Instances: " +  awsScalingGroup.getMaxInstances());
        adminCommandContext.getActionReport().appendMessage("\nPayara Installation Dir: " +  awsScalingGroup.getPayaraInstallDir());
        adminCommandContext.getActionReport().appendMessage("\nPassword File Path: " +  awsScalingGroup.getPasswordFilePath());

        Properties extraProps = new Properties();
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("name", awsScalingGroup.getName());
        configMap.put("config", awsScalingGroup.getConfigRef());
        configMap.put("deploymentGroup", awsScalingGroup.getDeploymentGroupRef());
        configMap.put("region", awsScalingGroup.getRegion());
        configMap.put("instanceType", awsScalingGroup.getInstanceType());
        configMap.put("amiId", awsScalingGroup.getAmiId());
        configMap.put("securityGroup", awsScalingGroup.getSecurityGroup());
        configMap.put("minInstances", awsScalingGroup.getMinInstances());
        configMap.put("maxInstances", awsScalingGroup.getMaxInstances());
        configMap.put("installDir", awsScalingGroup.getPayaraInstallDir());
        configMap.put("passwordFilePath", awsScalingGroup.getPasswordFilePath());

        extraProps.put("scalingGroupConfig", configMap);
        adminCommandContext.getActionReport().setExtraProperties(extraProps);
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
    }
}
