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
package fish.payara.extensions.autoscale.groups;

import com.sun.enterprise.util.StringUtils;
import fish.payara.enterprise.config.serverbeans.DeploymentGroups;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.jvnet.hk2.annotations.Contract;

import javax.inject.Inject;
import java.util.logging.Logger;

/**
 * Contract class for AutoScale Group service implementations.
 *
 * @author Andrew Pielage
 */
@Contract
public abstract class Scaler {

    public static final String AUTOSCALE_MAXSCALE_PROP = "fish.payara.autoscale.maxscale";
    public static final int AUTOSCALE_MAXSCALE_DEFAULT = 100;

    @Inject
    protected ServiceLocator serviceLocator;

    @Inject
    protected ScalingGroups scalingGroups;

    @Inject
    protected DeploymentGroups deploymentGroups;

    /**
     * Scale up the number of instances in the given Deployment Group by the specified amount.
     *
     * @param numberOfNewInstances The number of instances to scale the Deployment Group up in size by.
     * @param scalingGroup         The {@link ScalingGroup Scaling Group} config to use for scaling, holding reference to
     *                             the {@link fish.payara.enterprise.config.serverbeans.DeploymentGroup Deployment Group} to
     *                             scale, which {@link com.sun.enterprise.config.serverbeans.Config Instance Config} to use, as
     *                             well as any additional implementation specific information.
     * @return An {@link ActionReport} detailing the outcome of the operation
     */
    public abstract ActionReport scaleUp(int numberOfNewInstances, ScalingGroup scalingGroup);

    /**
     * Scale down the number of instances in the given Deployment Group by the specified amount.
     *
     * @param numberOfInstancesToRemove The number of instances to scale the Deployment Group down in size by.
     * @param scalingGroup              The {@link ScalingGroup Scaling Group} config to use for scaling, holding reference to
     *                                  the {@link fish.payara.enterprise.config.serverbeans.DeploymentGroup Deployment Group} to
     *                                  scale, as well as any additional implementation specific information.
     * @return An {@link ActionReport} detailing the outcome of the operation
     */
    public abstract ActionReport scaleDown(int numberOfInstancesToRemove, ScalingGroup scalingGroup);

    public Class<? extends ScalingGroup> getScalingGroupClass() {
        return getClass().getAnnotation(ScalerFor.class).value();
    }

    /**
     * Method to validate that everything required for scaling an instance up or down is initialised
     * and valid.
     *
     * @param numberOfInstances The number of instances to scale up or down.
     * @param scalingGroup      The {@link ScalingGroup Scaling Group} configuration to use for scaling
     */
    protected void validate(int numberOfInstances, ScalingGroup scalingGroup)
            throws CommandValidationException {
        if (serviceLocator == null) {
            serviceLocator = Globals.getDefaultBaseServiceLocator();

            if (serviceLocator == null) {
                throw new CommandValidationException("Could not find or initialise Service Locator!");
            }
        }

        if (scalingGroup == null) {
            throw new CommandValidationException("Scaling Group appears to be null!");
        }

        if (scalingGroups == null) {
            scalingGroups = serviceLocator.getService(ScalingGroups.class);

            if (scalingGroups == null) {
                throw new CommandValidationException("Could not find or initialise Scaling Groups!");
            }
        }

        if (deploymentGroups == null) {
            deploymentGroups = serviceLocator.getService(DeploymentGroups.class);

            if (deploymentGroups == null) {
                throw new CommandValidationException("Could not find Deployment Groups!");
            }
        }

        if (!StringUtils.ok(scalingGroup.getDeploymentGroupRef())) {
            throw new CommandValidationException("Scaling Group " + scalingGroup.getName() +
                    " has an invalid Deployment Group configured: " + scalingGroup.getDeploymentGroupRef());
        }

        if (deploymentGroups.getDeploymentGroup(scalingGroup.getDeploymentGroupRef()) == null) {
            throw new CommandValidationException("Deployment Group " + scalingGroup.getDeploymentGroupRef() +
                    " does not appear to exist!");
        }

        int maxScale = Integer.getInteger(AUTOSCALE_MAXSCALE_PROP, AUTOSCALE_MAXSCALE_DEFAULT);

        if (maxScale < 1) {
            Logger.getLogger(Scaler.class.getName()).warning(
                    AUTOSCALE_MAXSCALE_PROP + " property evaluated to less than 1, defaulting to " +
                            AUTOSCALE_MAXSCALE_DEFAULT);
            maxScale = AUTOSCALE_MAXSCALE_DEFAULT;
        }

        if (numberOfInstances < 1 || numberOfInstances > maxScale) {
            throw new CommandValidationException("Invalid number of instances to scale: " + numberOfInstances +
                    ". Number of instances to scale must be greater than 1 and less than " + maxScale);
        }
    }
}