/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */

package org.openmrs.module.cdshooks.client;

/**
 * Thrown when the terminology server (Snowstorm) is unreachable or returns a
 * 5xx error. Signals that the algorithm can't run — distinct from "concept
 * not found" (4xx), which is a legitimate no-data result.
 */
public class TerminologyUnavailableException extends RuntimeException {
    public TerminologyUnavailableException(String message) {
        super(message);
    }

    public TerminologyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
