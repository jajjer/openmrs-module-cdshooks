/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.api;

import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.openmrs.module.cdshooks.model.CdsHooksResponse;

public interface CdsHooksService {

    /**
     * Evaluate a CDS-Hooks request for the drug-allergy service. Inspects the
     * draft MedicationRequest in the request context, fetches the patient's
     * allergies, and returns a CDS-Hooks response containing zero or more
     * warning Cards for any ingredient or class matches against the
     * configured SNOMED CT terminology server.
     *
     * @param request the parsed CDS-Hooks invocation
     * @return the CDS-Hooks response with Cards (may be empty)
     */
    CdsHooksResponse evaluateDrugAllergy(CdsHooksRequest request);
}
