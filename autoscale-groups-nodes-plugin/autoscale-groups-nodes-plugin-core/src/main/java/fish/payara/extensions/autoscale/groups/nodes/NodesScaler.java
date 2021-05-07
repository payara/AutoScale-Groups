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

package fish.payara.extensions.autoscale.groups.nodes;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Nodes;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.util.StringUtils;
import fish.payara.enterprise.config.serverbeans.DeploymentGroup;
import fish.payara.extensions.autoscale.groups.Scaler;
import fish.payara.extensions.autoscale.groups.Scales;
import fish.payara.extensions.autoscale.groups.ScalingGroup;
import fish.payara.extensions.autoscale.groups.core.admin.ScaleCommandHelper;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.internal.api.InternalSystemAdministrator;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@link Scaler} implementation service which performs the scale up and down procedures across a selection of
 * {@link com.sun.enterprise.config.serverbeans.Nodes Nodes}.
 *
 * @author Andrew Pielage
 */
@Service
@Scales(NodesScalingGroup.class)
public class NodesScaler extends Scaler {

    @Inject
    private InternalSystemAdministrator internalSystemAdministrator;

    @Inject
    private CommandRunner commandRunner;

    @Inject
    private Nodes nodes;

    private static final Logger LOGGER = Logger.getLogger(NodesScaler.class.getName());

    @Override
    protected void validate(int numberOfInstances, ScalingGroup scalingGroup) throws CommandValidationException {
        if (commandRunner == null) {
            commandRunner = serviceLocator.getService(CommandRunner.class);

            if (commandRunner == null) {
                throw new CommandValidationException(
                        "Could not find or initialise CommandRunner to execute commands with!");
            }
        }

        if (internalSystemAdministrator == null) {
            internalSystemAdministrator = serviceLocator.getService(InternalSystemAdministrator.class);

            if (internalSystemAdministrator == null) {
                throw new CommandValidationException(
                        "Could not find or initialise InternalSystemAdministrator to execute commands with!");
            }
        }

        List<NodesScalingGroup> nodesScalingGroups = scalingGroups.getScalingGroupsOfType(NodesScalingGroup.class);

        if (nodesScalingGroups.isEmpty()) {
            throw new CommandValidationException("No Nodes Scaling Groups found!");
        }

        boolean exists = false;
        for (NodesScalingGroup nodesScalingGroup : nodesScalingGroups) {
            if (nodesScalingGroup.getName().equals(scalingGroup.getName())) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            throw new CommandValidationException("Scaling Group " + scalingGroup.getName() +
                    " is not a Nodes Scaling Group.");
        }

        if (nodes == null) {
            nodes = serviceLocator.getService(Nodes.class);

            if (nodes == null) {
                throw new CommandValidationException("Could not find Nodes!");
            }
        }

        for (String nodeRef : ((NodesScalingGroup) scalingGroup).getNodeRefs()) {
            if (!StringUtils.ok(nodeRef)) {
                throw new CommandValidationException("Scaling Group has an invalid node reference configured: " + nodeRef);
            }

            if (nodes.getNode(nodeRef) == null) {
                throw new CommandValidationException("Node " + nodeRef + " does not appear to exist!");
            }
        }
    }

    @Override
    public void scaleUp(int numberOfNewInstances, ScalingGroup scalingGroup) {
        try {
            validate(numberOfNewInstances, scalingGroup);
        } catch (CommandValidationException commandValidationException) {
            LOGGER.severe("Cancelling scale up operation, an error was encountered during validation");
            return;
        }

        try {
            List<String> instanceNames = createInstances(numberOfNewInstances, scalingGroup);
            startInstances(instanceNames);
        } catch (CommandException commandException) {
            LOGGER.severe(commandException.getMessage());
        }
    }

    private List<String> createInstances(int numberOfNewInstances, ScalingGroup scalingGroup) throws CommandException {
        List<String> instanceNames = new ArrayList<>();

        // Get the starting balance of instances to nodes
        DeploymentGroup deploymentGroup = deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef());
        Map<String, Integer> scalingGroupBalance = getNodesInstanceBalance(
                deploymentGroup.getInstances(), ((NodesScalingGroup) scalingGroup).getNodeRefs());

        // Determine where to create each instance
        int instanceCounter = 0;
        while (instanceCounter < numberOfNewInstances) {
            // Get the node with the least instances
            Map.Entry<String, Integer> maxNodeEntry = Collections.min(
                    scalingGroupBalance.entrySet(), Comparator.comparing(Map.Entry::getValue));

            // Create the parameter map for the create-instance command
            ParameterMap parameterMap = new ParameterMap();
            parameterMap.add("deploymentgroup", scalingGroup.getDeploymentGroupRef());
            if (!scalingGroup.getConfigRef().equals("default-config")) {
                parameterMap.add("config", scalingGroup.getConfigRef());
            }
            parameterMap.add("autoname", "true");
            parameterMap.add("extraterse", "true");
            parameterMap.add("node", maxNodeEntry.getKey());

            // Execute the command with our parameters - we don't want to execute them in a parallel manner since
            // we'll run into issues with locking on the config beans
            ActionReport actionReport = commandRunner.getActionReport("plain");
            CommandRunner.CommandInvocation createInstanceCommand = commandRunner.getCommandInvocation(
                    "create-instance", actionReport, internalSystemAdministrator.getSubject());
            createInstanceCommand.parameters(parameterMap);
            createInstanceCommand.execute();

            // Check if we have any failures - we don't want to continue if any failed
            if (actionReport.hasFailures()) {
                LOGGER.severe("Encountered an error scaling up instances. " +
                        instanceCounter + " were created out of the requested " + numberOfNewInstances + ". " +
                        "The error encountered was: " + actionReport.getFailureCause().getMessage());
                throw new CommandException("Encountered an error scaling up instances.", actionReport.getFailureCause());
            }

            // The output of the create-instance command with the "extraterse" option should just be the instance name
            instanceNames.add(actionReport.getMessage());

            // Adjust the node balance
            scalingGroupBalance.put(maxNodeEntry.getKey(), maxNodeEntry.getValue() + 1);

            // Increment the counter for our loop
            instanceCounter++;
        }

        return instanceNames;
    }

    private void startInstances(List<String> instanceNames) {
        ScaleCommandHelper scaleCommandHelper = new ScaleCommandHelper(serviceLocator.getService(Domain.class),
                commandRunner, internalSystemAdministrator.getSubject());
        scaleCommandHelper.runCommandInParallelAcrossInstances("start-instance", new ParameterMap(), instanceNames, true);
    }

    @Override
    public void scaleDown(int numberOfInstancesToRemove, ScalingGroup scalingGroup) {
        try {
            validate(numberOfInstancesToRemove, scalingGroup);
        } catch (CommandValidationException commandValidationException) {
            LOGGER.severe("Cancelling scale down operation, an error was encountered during validation");
            return;
        }

        try {
            List<String> instanceNames = determineInstancesToStop(numberOfInstancesToRemove, scalingGroup);
            stopInstances(instanceNames);
            deleteInstances(instanceNames);
        } catch (CommandException commandException) {
            LOGGER.severe(commandException.getMessage());
        }
    }

    private List<String> determineInstancesToStop(int numberOfInstancesToRemove, ScalingGroup scalingGroup)
            throws CommandException {
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

        // Get the balance of instances across the nodes within this Deployment Group and Scaling Group
        List<String> nodeRefs = ((NodesScalingGroup) scalingGroup).getNodeRefs();
        Map<String, Integer> scalingGroupBalance = getNodesInstanceBalance(instances, nodeRefs);

        // Loop until we've removed the requested number of instances
        int instanceCounter = numberOfInstancesToRemove;
        while (instanceCounter > 0) {
            // Get the node with the most instances
            Map.Entry<String, Integer> maxNodeEntry = Collections.max(
                    scalingGroupBalance.entrySet(), Comparator.comparing(Map.Entry::getValue));

            // Pick an instance from that node
            for (Server server : instances) {
                if (!instanceNames.contains(server.getName()) && server.getNodeRef().equals(maxNodeEntry.getKey())) {
                    instanceNames.add(server.getName());
                    scalingGroupBalance.put(maxNodeEntry.getKey(), maxNodeEntry.getValue() - 1);
                    break;
                }
            }

            instanceCounter--;
        }

        return instanceNames;
    }

    private Map<String, Integer> getNodesInstanceBalance(List<Server> instances, List<String> nodeRefs) {
        Map<String, Integer> scalingGroupBalance = new HashMap<>();
        for (String nodeRef : nodeRefs) {
            scalingGroupBalance.put(nodeRef, 0);
        }

        for (Server instance : instances) {
            scalingGroupBalance.put(instance.getNodeRef(), scalingGroupBalance.get(instance.getNodeRef()) + 1);
        }

        return scalingGroupBalance;
    }

    private void stopInstances(List<String> instanceNames) {
        ScaleCommandHelper scaleCommandHelper = new ScaleCommandHelper(serviceLocator.getService(Domain.class),
                commandRunner, internalSystemAdministrator.getSubject());
        scaleCommandHelper.runCommandInParallelAcrossInstances("stop-instance", new ParameterMap(),
                instanceNames, true);
    }

    private void deleteInstances(List<String> instanceNames) {
        for (String instanceName : instanceNames) {
            ActionReport actionReport = commandRunner.getActionReport("plain");
            CommandRunner.CommandInvocation deleteInstanceCommand = commandRunner.getCommandInvocation(
                    "delete-instance", actionReport, internalSystemAdministrator.getSubject());

            ParameterMap parameterMap = new ParameterMap();
            // Primary parameter is called DEFAULT, regardless of its actual name
            parameterMap.add("DEFAULT", instanceName);

            deleteInstanceCommand.parameters(parameterMap);
            deleteInstanceCommand.execute();
        }
    }
}