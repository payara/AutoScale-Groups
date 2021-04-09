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

import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.Domain;
import fish.payara.enterprise.config.serverbeans.DeploymentGroup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

/**
 * Validator implementation for checking that a {@link ScalingGroup} ConfigBean has a 1:1 relationship with a
 * {@link fish.payara.enterprise.config.serverbeans.DeploymentGroup}.
 *
 * @author Andrew Pielage
 */
public class NotDuplicateDeploymentGroupTargetValidator
        implements ConstraintValidator<NotDuplicateDeploymentGroupTarget, String> {

    Domain domain = null;

    @Override
    public void initialize(NotDuplicateDeploymentGroupTarget constraintAnnotation) {
        ServiceLocator locator = ServiceLocatorFactory.getInstance().find("default");
        if (locator == null) {
            return;
        }

        ConfigBeansUtilities configBeansUtilities = locator.getService(ConfigBeansUtilities.class);
        if (configBeansUtilities == null) {
            return;
        }

        domain = configBeansUtilities.getDomain();
    }

    @Override
    public boolean isValid(String name, ConstraintValidatorContext constraintValidatorContext) {
        if (domain == null) {
            return true;
        }

        // Look up a Deployment Group with the given name
        DeploymentGroup deploymentGroup = domain.getDeploymentGroupNamed(name);

        /**
         * If null, there isn't a {@link DeploymentGroup} with that name, so return true (since there technically isn't
         * a duplicate). Validation of the {@link DeploymentGroup} existing is handled by
         * {@link com.sun.enterprise.config.serverbeans.customvalidators.ReferenceConstraint}
         */
        if (deploymentGroup == null) {
            return true;
        }

        // Look up all other scaling groups
        ScalingGroups scalingGroups = domain.getExtensionByType(ScalingGroups.class);
        if (scalingGroups == null) {
            return true;
        }

        // Search through the scaling groups, checking for any matches of the deployment group ref
        for (ScalingGroup scalingGroup : scalingGroups.getScalingGroups()) {
            // If we find a match, return false - the deployment group is already in use
            if (scalingGroup.getDeploymentGroupRef().equals(name)) {
                return false;
            }
        }

        return true;
    }
}
