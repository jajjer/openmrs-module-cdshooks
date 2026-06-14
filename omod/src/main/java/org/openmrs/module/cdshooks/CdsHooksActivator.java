/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks;

import org.openmrs.module.BaseModuleActivator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CdsHooksActivator extends BaseModuleActivator {

    private static final Logger log = LoggerFactory.getLogger(CdsHooksActivator.class);

    @Override
    public void started() {
        log.info("CDS Hooks Module started");
    }

    @Override
    public void stopped() {
        log.info("CDS Hooks Module stopped");
    }
}
