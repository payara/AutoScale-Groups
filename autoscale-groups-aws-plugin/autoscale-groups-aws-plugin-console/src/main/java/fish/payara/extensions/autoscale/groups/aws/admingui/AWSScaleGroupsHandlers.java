package fish.payara.extensions.autoscale.groups.aws.admingui;

import com.sun.jsftemplating.annotation.Handler;
import com.sun.jsftemplating.annotation.HandlerOutput;
import com.sun.jsftemplating.layout.descriptors.handler.HandlerContext;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.InstanceType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AWSScaleGroupsHandlers {

    @Handler(id = "py.getValidInstanceTypes",
            output = {
                    @HandlerOutput(name = "result", type = List.class)
            })
    public static void getValidInstanceTypes(HandlerContext handlerCtx) {

        handlerCtx.setOutputValue("result", InstanceType.knownValues().stream().map(InstanceType::toString).collect(Collectors.toList()));
    }

    @Handler(id = "py.getValidRegions",
            output = {
                    @HandlerOutput(name = "result", type = List.class)
            })
    public static void getValidRegions(HandlerContext handlerCtx) {

        handlerCtx.setOutputValue("result", Region.regions().stream().map(Region::toString).collect(Collectors.toList()));
    }
}
