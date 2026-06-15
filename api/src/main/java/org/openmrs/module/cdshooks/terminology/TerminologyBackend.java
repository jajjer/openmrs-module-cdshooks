/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.terminology;

import org.openmrs.module.cdshooks.model.CodedConcept;
import org.openmrs.module.cdshooks.model.SubsumptionOutcome;

import java.util.List;

/**
 * The two terminology primitives the drug-allergy matcher needs, abstracted
 * away from any single terminology server.
 *
 * <p>The matcher originally spoke only to a live Snowstorm instance over FHIR
 * ({@code $lookup} / {@code $subsumes}). Andrew Kanter's feedback on the Talk
 * thread pointed at a second, more OpenMRS-native source for the same
 * knowledge: the {@code concept_reference_term_map} table, which "is intended
 * to capture hierarchies and other relationships between reference codes" —
 * including the RxClass NUI ↔ RxNORM CUI class edges. Loading those edges
 * locally lets the parent-child lookup run without a live terminology server.
 *
 * <p>This interface is the seam that lets a deployment choose either source
 * (or both) behind the same {@link org.openmrs.module.cdshooks.api.AllergyMatcher}.
 *
 * <p><b>Note:</b> {@link CodedConcept} is used here as a generic (code,
 * display) value object, not as a SNOMED-only type. A rename to a neutral
 * {@code CodedConcept} is a worthwhile follow-up now that the matcher is no
 * longer SNOMED-exclusive.
 */
public interface TerminologyBackend {

    /**
     * Stable identifier for this backend, used by the router to select among
     * implementations via the {@code cdshooks.terminologyBackend} global
     * property.
     */
    String name();

    /**
     * Returns the values of a bridging attribute on a concept — e.g. all
     * {@code Causative agent} values on an allergen finding, or all
     * {@code Has active ingredient} values on a drug product. Empty list if
     * the attribute is absent or the backend has no notion of attribute
     * relationships (in which case the matcher falls back to direct
     * code-to-code subsumption — see
     * {@code AllergyMatcherImpl.expandToSubstances}).
     *
     * @param conceptCode   the code to inspect (e.g. a SNOMED SCTID)
     * @param attributeCode the SNOMED attribute SCTID to read
     */
    List<CodedConcept> getAttributeValues(String conceptCode, String attributeCode);

    /**
     * Answers whether {@code ancestorCode} subsumes {@code descendantCode} in
     * the backend's hierarchy.
     *
     * @param ancestorCode   the candidate broader/parent code
     * @param descendantCode the candidate narrower/child code
     * @return the subsumption outcome; {@link SubsumptionOutcome#UNKNOWN} if it
     *         cannot be determined
     */
    SubsumptionOutcome subsumes(String ancestorCode, String descendantCode);
}
