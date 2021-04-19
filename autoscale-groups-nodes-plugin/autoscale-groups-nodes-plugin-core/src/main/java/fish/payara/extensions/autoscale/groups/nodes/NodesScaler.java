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

import com.sun.enterprise.config.serverbeans.Nodes;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.util.StringUtils;
import fish.payara.enterprise.config.serverbeans.DeploymentGroup;
import fish.payara.enterprise.config.serverbeans.DeploymentGroups;
import fish.payara.extensions.autoscale.groups.Scaler;
import fish.payara.extensions.autoscale.groups.Scales;
import fish.payara.extensions.autoscale.groups.ScalingGroup;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.InternalSystemAdministrator;
import org.jvnet.hk2.annotations.Service;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@link Scaler} implementation service which performs the scale up and down procedures across a selection of
 * {@link com.sun.enterprise.config.serverbeans.Nodes Nodes}.
 *
 * @author Andrew Pielage
 */
@Service
@Scales(NodesScalingGroup.class)
public class NodesScaler implements Scaler {

    @Inject
    private ServiceLocator serviceLocator;

    @Inject
    private InternalSystemAdministrator internalSystemAdministrator;

    @Inject
    private CommandRunner commandRunner;

    @Inject
    private DeploymentGroups deploymentGroups;

    @Inject
    private Nodes nodes;

    private static final Logger LOGGER = Logger.getLogger(NodesScaler.class.getName());

    @Override
    public void scaleUp(int numberOfNewInstances, ScalingGroup scalingGroup) {
        if (!initialiseAndValidate(numberOfNewInstances, scalingGroup)) {
            LOGGER.severe("Cancelling scale up operation, an error was encountered during validation");
            return;
        }

        try {
            Set<String> instanceNames = createInstances(numberOfNewInstances, scalingGroup);
            startInstances(instanceNames);
        } catch (CommandException commandException) {
            LOGGER.severe(commandException.getMessage());
        }
    }

    /**
     * Method to initialise and validate that everything required for scaling an instance up or down is initialised
     * and valid.
     *
     * @param numberOfInstances The number of instances to scale up or down.
     * @param scalingGroup The {@link ScalingGroup Scaling Group} configuration to use for scaling
     * @return true if everything is initialised and valid, otherwise logs message as to what failed and returns false.
     */
    private boolean initialiseAndValidate(int numberOfInstances, ScalingGroup scalingGroup) {
        if (serviceLocator == null) {
            serviceLocator = Globals.getDefaultBaseServiceLocator();

            if (serviceLocator == null) {
                LOGGER.severe("Could not find or initialise Service Locator!");
                return false;
            }
        }

        if (commandRunner == null) {
            commandRunner = serviceLocator.getService(CommandRunner.class);

            if (commandRunner == null) {
                LOGGER.severe("Could not find or initialise CommandRunner to execute commands with!");
                return false;
            }
        }

        if (internalSystemAdministrator == null) {
            internalSystemAdministrator = serviceLocator.getService(InternalSystemAdministrator.class);

            if (internalSystemAdministrator == null) {
                LOGGER.severe("Could not find or initialise InternalSystemAdministrator to execute commands with!");
                return false;
            }
        }

        if (scalingGroup == null) {
            LOGGER.severe("Scaling Group appears to be null!");
            return false;
        }

        if (!(scalingGroup instanceof NodesScalingGroup)) {
            LOGGER.severe("Scaling Group does not appear to be one of the correct type!");
            return false;
        }

        if (deploymentGroups == null) {
            deploymentGroups = serviceLocator.getService(DeploymentGroups.class);

            if (deploymentGroups == null) {
                LOGGER.severe("Could not find Deployment Groups!");
                return false;
            }
        }

        if (!StringUtils.ok(scalingGroup.getDeploymentGroupRef())) {
            LOGGER.severe("Scaling Group " + scalingGroup.getName() +
                    " has an invalid Deployment Group configured: " + scalingGroup.getDeploymentGroupRef());
            return false;
        }

        if (deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef()) == null) {
            LOGGER.severe("Deployment Group " + scalingGroup.getDeploymentGroupRef() + " does not appear to exist!");
            return false;
        }

        if (numberOfInstances < 1) {
            LOGGER.warning("Invalid number of instances to scale: " + numberOfInstances +
                    ". Number of instances to scale must be greater than 1");
            return false;
        }

        if (nodes == null) {
            nodes = serviceLocator.getService(Nodes.class);

            if (nodes == null) {
                LOGGER.severe("Could not find Nodes!");
                return false;
            }
        }

        for (String nodeRef : ((NodesScalingGroup) scalingGroup).getNodeRefs()) {
            if (!StringUtils.ok(nodeRef)) {
                LOGGER.severe("Scaling Group has an invalid node reference configured: " + nodeRef);
                return false;
            }

            if (nodes.getNode(nodeRef) == null) {
                LOGGER.severe("Node " + nodeRef + " does not appear to exist!");
                return false;
            }
        }

        return true;
    }

    private Set<String> createInstances(int numberOfNewInstances, ScalingGroup scalingGroup) throws CommandException {
        Set<String> instanceNames = new HashSet<>();
        int instanceCounter = 0;
        while (instanceCounter < numberOfNewInstances) {
            ActionReport actionReport = commandRunner.getActionReport("plain");
            CommandRunner.CommandInvocation createInstanceCommand = commandRunner.getCommandInvocation(
                    "create-instance", actionReport, internalSystemAdministrator.getSubject());

            ParameterMap parameterMap = new ParameterMap();
            parameterMap.add("deploymentgroup", scalingGroup.getDeploymentGroupRef());
            parameterMap.add("config", scalingGroup.getConfigRef());
            parameterMap.add("autoname", "true");
            parameterMap.add("extraterse", "true");

            createInstanceCommand.parameters(parameterMap);
            createInstanceCommand.execute();

            if (actionReport.hasFailures()) {
                LOGGER.severe("Encountered an error scaling up instances. " +
                        instanceCounter + " were created out of the requested " + numberOfNewInstances + ". " +
                        "The error encountered was: " + actionReport.getFailureCause().getMessage());
                throw new CommandException("Encountered an error scaling up instances.", actionReport.getFailureCause());
            }

            // The output of the create-instance command with the "extraterse" option should just be the instance name
            instanceNames.add(actionReport.getMessage());

            instanceCounter++;
        }

        return instanceNames;
    }

    private void startInstances(Set<String> instanceNames) {
        for (String instanceName : instanceNames) {
            ActionReport actionReport = commandRunner.getActionReport("plain");
            CommandRunner.CommandInvocation startInstanceCommand = commandRunner.getCommandInvocation(
                    "start-instance", actionReport, internalSystemAdministrator.getSubject());

            ParameterMap parameterMap = new ParameterMap();
            // Primary parameter is called DEFAULT, regardless of its actual name
            parameterMap.add("DEFAULT", instanceName);

            startInstanceCommand.parameters(parameterMap);
            startInstanceCommand.execute();
        }
    }

    @Override
    public void scaleDown(int numberOfInstancesToRemove, ScalingGroup scalingGroup) {
        if (!initialiseAndValidate(numberOfInstancesToRemove, scalingGroup)) {
            LOGGER.severe("Cancelling scale down operation, an error was encountered during validation");
            return;
        }

        try {
            Set<String> instanceNames = determineInstancesToStop(numberOfInstancesToRemove, scalingGroup);
            stopInstances(instanceNames);
            deleteInstances(instanceNames);
        } catch (CommandException commandException) {
            LOGGER.severe(commandException.getMessage());
        }
    }

    private Set<String> determineInstancesToStop(int numberOfInstancesToRemove, ScalingGroup scalingGroup)
            throws CommandException {
        Set<String> instanceNames = new HashSet<>();

        // Existence of deployment group checked in NodesScaler#initialiseAndValidate method so no need to check again
        DeploymentGroup deploymentGroup = deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef());

        // Get the balance of instances across the nodes within this Deployment Group and Scaling Group
        List<Server> instances = deploymentGroup.getInstances();

        // Existence of nodes and type of scalingGroup checked in NodesScaler#initialiseAndValidate method
        List<String> nodeRefs = ((NodesScalingGroup) scalingGroup).getNodeRefs();

        // Get current balance
        Map<String, Integer> scalingGroupBalance = new HashMap<>();
        for (String nodeRef : nodeRefs) {
            scalingGroupBalance.put(nodeRef, 0);
        }

        for (Server instance : instances) {
            scalingGroupBalance.put(instance.getNodeRef(), scalingGroupBalance.get(instance.getNodeRef()) + 1);
        }
        
        return instanceNames;
    }

    private void stopInstances(Set<String> instanceNames) {

    }

    private void deleteInstances(Set<String> deleteInstances) {

    }
}
