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
