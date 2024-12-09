package fish.payara.extensions.autoscale.groups.aws;

import fish.payara.extensions.autoscale.groups.Scaler;
import fish.payara.extensions.autoscale.groups.ScalerFor;
import fish.payara.extensions.autoscale.groups.ScalingGroup;
import jakarta.inject.Inject;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.CommandValidationException;
import org.jvnet.hk2.annotations.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.InstanceType;

@Service
@ScalerFor(AWSScalingGroup.class)
public class AWSScaler extends Scaler {

    @Inject
    private CommandRunner commandRunner;


    @Override
    protected void validate(int numberOfInstances, ScalingGroup scalingGroup) throws CommandValidationException {
        super.validate(numberOfInstances, scalingGroup);
        AWSScalingGroup awsScalingGroup = (AWSScalingGroup) scalingGroup;
        if (awsScalingGroup.getMinInstances() < 0 ) {
            throw new CommandValidationException("Min instances must be greater than zero");
        }

        if (awsScalingGroup.getMaxInstances() < awsScalingGroup.getMinInstances() ) {
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
    }

    /*
     * Order of this should be:
     * 1. Validate Configuration (instances, total instances <= max, aws info)
     * 2. Create AWS Instance and Security Group etc
     * 3. Create SSH Node
     * 4. Create Instance
     * 5. ... to be confirmed
     */
    @Override
    public ActionReport scaleUp(int numberOfNewInstances, ScalingGroup scalingGroup) {
        ActionReport actionReport = commandRunner.getActionReport("plain");

        try {
            validate(numberOfNewInstances, scalingGroup);
        } catch (CommandValidationException commandValidationException) {
            actionReport.setMessage("Scale up operation cancelled: an error was encountered during validation");
            actionReport.setFailureCause(commandValidationException);
            actionReport.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return actionReport;
        }
        actionReport.setMessage("validation passed");
        actionReport.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        return actionReport;
    }

    @Override
    public ActionReport scaleDown(int numberOfInstancesToRemove, ScalingGroup scalingGroup) {
        return null;
    }
}
