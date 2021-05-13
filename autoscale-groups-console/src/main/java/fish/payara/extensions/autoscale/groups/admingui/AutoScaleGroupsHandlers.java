/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2021 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package fish.payara.extensions.autoscale.groups.admingui;

import com.sun.enterprise.util.StringUtils;
import com.sun.jsftemplating.annotation.Handler;
import com.sun.jsftemplating.annotation.HandlerInput;
import com.sun.jsftemplating.annotation.HandlerOutput;
import com.sun.jsftemplating.layout.descriptors.handler.HandlerContext;
import org.glassfish.admingui.common.util.GuiUtil;
import org.glassfish.admingui.common.util.RestResponse;
import org.glassfish.admingui.common.util.RestUtil;

import javax.xml.ws.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoScaleGroupsHandlers {

    @Handler(id = "py.getScalingGroupConfig",
            input = {
                    @HandlerInput(name = "endpoint", type = String.class, required = true),
                    @HandlerInput(name = "scalingGroupName", type = String.class, required = true)
            },
            output = {
                    @HandlerOutput(name = "result", type = Map.class)
            })
    public static void getScalingGroupConfig(HandlerContext handlerCtx) {
        try {
            // First check if we've actually been given a name
            String scalingGroupName = (String) handlerCtx.getInputValue("scalingGroupName");
            if (!StringUtils.ok(scalingGroupName)) {
                return;
            }

            // Get all scaling group types
            Map<String, String> scalingGroupTypesMap = RestUtil.getChildMap((String) handlerCtx.getInputValue("endpoint"));

            // Search for our scaling group
            for (Map.Entry<String, String> scalingGroupType : scalingGroupTypesMap.entrySet()) {
                Map<String, String> scalingGroupEntities = RestUtil.getChildMap(scalingGroupType.getValue());

                for (Map.Entry<String, String> scalingGroupEntity : scalingGroupEntities.entrySet()) {
                    if (scalingGroupEntity.getKey().equals(scalingGroupName)) {
                        // Found it
                        Map<String, Object> scalingGroup = null;

                        // Check if there is a get command we can use (assuming pattern of get-xyz-configuration)
                        String getCommandEndpoint = scalingGroupEntity.getValue() + "/get-" +
                                scalingGroupType.getKey() + "-configuration";
                        RestResponse getCommandResponse = RestUtil.get(getCommandEndpoint);
                        // If there is, use it. The reason for this is because querying entities doesn't account for
                        // child elements (e.g. server references), whereas we can assume a get-xxx-configuration
                        // command would return all pertinent information
                        if (getCommandResponse.isSuccess()) {
                            scalingGroup = RestUtil.parseResponse(getCommandResponse, handlerCtx, getCommandEndpoint,
                                    null, true, true);
                        } else {
                            // If there isn't a get command, just attempt to get the entity attributes
                            scalingGroup = RestUtil.getAttributesMap(scalingGroupEntity.getValue());
                        }

                        // Return
                        handlerCtx.setOutputValue("result", scalingGroup);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

    @Handler(id = "py.getScalingGroupsList",
            input = {
                    @HandlerInput(name = "endpoint", type = String.class, required = true)
            },
            output = {
                    @HandlerOutput(name = "result", type = List.class)
            })
    public static void getScalingGroupsList(HandlerContext handlerCtx) {
        try {
            // Get all scaling group types
            Map<String, String> scalingGroupTypesMap = RestUtil.getChildMap((String) handlerCtx.getInputValue("endpoint"));

            // Add empty string to return list so that the dropdown has a default
            List<String> scalingGroupNames = new ArrayList<>();
            scalingGroupNames.add("");

            // For each type, get all scaling groups and add them to a List
            for (Map.Entry<String, String> scalingGroupType : scalingGroupTypesMap.entrySet()) {
                Map<String, String> scalingGroupEntities = RestUtil.getChildMap(scalingGroupType.getValue());
                scalingGroupNames.addAll(scalingGroupEntities.keySet());
            }

            // Return
            handlerCtx.setOutputValue("result", scalingGroupNames);
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }



    @Handler(id = "py.setDeploymentGroupScalingGroup",
            input = {
                    @HandlerInput(name = "endpoint", type = String.class, required = true),
                    @HandlerInput(name = "deploymentGroupName", type = String.class, required = true),
                    @HandlerInput(name = "scalingGroupName", type = String.class, required = true),
            },
            output = {
                    @HandlerOutput(name = "result", type = String.class)
            })
    public static void setDeploymentGroupScalingGroup(HandlerContext handlerCtx) {
        try {
            String endpoint = (String) handlerCtx.getInputValue("endpoint");
            String deploymentGroupName = (String) handlerCtx.getInputValue("deploymentGroupName");
            String scalingGroupName = (String) handlerCtx.getInputValue("scalingGroupName");

            Map<String, Object> commandParameters = new HashMap<>();
            commandParameters.put("deploymentgroup", deploymentGroupName);

            // Get all scaling group types
            Map<String, String> scalingGroupTypesMap = RestUtil.getChildMap(endpoint);

            // Search for our scaling group
            for (Map.Entry<String, String> scalingGroupType : scalingGroupTypesMap.entrySet()) {
                Map<String, String> scalingGroupEntities = RestUtil.getChildMap(scalingGroupType.getValue());

                for (Map.Entry<String, String> scalingGroupEntity : scalingGroupEntities.entrySet()) {
                    if (scalingGroupEntity.getKey().equals(scalingGroupName)) {
                        // Found it - execute set command
                        String setCommandEndpoint = scalingGroupEntity.getValue() + "/set-" +
                                scalingGroupType.getKey() + "-configuration";
                        Map<String, Object> result = RestUtil.restRequest(
                                setCommandEndpoint, commandParameters, "post", handlerCtx, true);

                        // Return
                        handlerCtx.setOutputValue("result", result);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

}