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
