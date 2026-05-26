/**
 * E2E: confirm the drug-allergy warning Card renders inline in the medication
 * order basket when a clinician orders Amoxicillin for a patient with a
 * recorded coded penicillin allergy.
 *
 * Pre-requisites:
 *   - Isolated cdshooks-dev stack running at http://localhost:8081
 *   - Frontend bundle wired into the SPA (importmap + routes.registry patched)
 *   - Backend module deployed
 *   - Test patient Alice Penicillin-Test (UUID below) has a recorded coded
 *     allergy to penicillin (CIEL 149071), severity Severe, reaction
 *     Hepatic toxicity
 *
 * Note: this spec stops short of actually clicking "add to basket" because
 * the precise selectors depend on how esm-patient-medications-app is built;
 * the SPA assemble process generates hashed class names. The shape of the
 * test is intentionally close to what shipping CI would run; selector
 * resilience can be hardened with data-testid attributes once the team
 * agrees on where to add them.
 */
import { test, expect } from "@playwright/test";

const PATIENT_UUID = "fbb64b8d-ada6-4182-8b90-ccce27b8a000";
const ADMIN_USER = "admin";
const ADMIN_PASSWORD = "Admin123";

test.describe("Drug-allergy warning in medication order basket", () => {
  test.beforeEach(async ({ page, request }) => {
    // Establish a session cookie via REST so we don't need to traverse the
    // SPA's full login flow.
    await request.get("/openmrs/ws/rest/v1/session", {
      headers: { Authorization: `Basic ${Buffer.from(`${ADMIN_USER}:${ADMIN_PASSWORD}`).toString("base64")}` },
    });
    // Copy the JSESSIONID into the page's browser context.
    const cookies = await request.storageState();
    await page.context().addCookies(cookies.cookies);
  });

  test("CDS-Hooks backend returns a critical warning for Amoxicillin", async ({ request }) => {
    // Direct backend smoke test — fast feedback that the warning generator works.
    const res = await request.post("/openmrs/ws/cds-services/drug-allergy", {
      data: {
        hook: "medication-prescribe",
        hookInstance: "e2e",
        context: {
          patientId: PATIENT_UUID,
          medications: {
            entry: [
              {
                resource: {
                  medicationCodeableConcept: {
                    text: "Amoxicillin",
                    coding: [{ system: "http://snomed.info/sct", code: "27658006" }],
                  },
                },
              },
            ],
          },
        },
      },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.cards).toBeDefined();
    expect(body.cards.length).toBeGreaterThan(0);
    const card = body.cards[0];
    expect(card.summary).toMatch(/Allergy to penicillin/i);
    expect(card.indicator).toBe("critical");
    expect(card.detail).toMatch(/Amoxicillin/);
  });

  test("Patient chart loads and the cdshooks module is registered in the SPA", async ({ page }) => {
    // Verify the SPA itself sees our module. We confirm in the actual page
    // context — anything that's been wired correctly will appear here.
    await page.goto(`/openmrs/spa/patient/${PATIENT_UUID}/chart`);

    // The login redirect lands on /spa/login if the session cookie didn't take.
    if (page.url().includes("/login")) {
      await page.fill('input[name="username"]', ADMIN_USER);
      await page.fill('input[name="password"]', ADMIN_PASSWORD);
      await page.click('button[type="submit"]');
      await page.goto(`/openmrs/spa/patient/${PATIENT_UUID}/chart`);
    }

    // Allow the SPA to load its module list.
    await page.waitForLoadState("networkidle");

    // The cdshooks module bundle should have been requested via importmap.
    const cdsModuleLoaded = await page.evaluate(async () => {
      const res = await fetch("/openmrs/spa/importmap.json");
      const map = await res.json();
      return Boolean(map.imports?.["@openmrs/esm-cdshooks-app"]);
    });
    expect(cdsModuleLoaded).toBe(true);
  });

  test.skip("Adding Amoxicillin to the basket renders a critical InlineNotification", async ({ page }) => {
    // SKIPPED for now: the precise selectors for the medication order
    // workspace depend on data-testid placement in esm-patient-medications-app.
    // Unblock by either:
    //   (a) adding stable data-testid attributes to the order-basket items, or
    //   (b) using accessible role/name queries once the order workflow is
    //       documented.
    await page.goto(`/openmrs/spa/patient/${PATIENT_UUID}/chart/orders`);
    // ... click "Add" -> search "amoxicillin" -> select -> "Save" or "Add to basket"
    // expect(page.getByRole("status").filter({ hasText: /Allergy to penicillin/i })).toBeVisible();
  });
});
