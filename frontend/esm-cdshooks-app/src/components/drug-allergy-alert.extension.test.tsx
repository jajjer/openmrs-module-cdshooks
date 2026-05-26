/**
 * Behaviour we care about for the order-basket allergy alert:
 *
 *  1. No drug SNOMED code  → render nothing (silent is correct when we can't check)
 *  2. Backend returns no cards → render nothing
 *  3. Backend returns a critical card → render an InlineNotification with kind="error",
 *     the card summary as title, and detail as subtitle.
 *  4. Backend error → render a visible "allergy check unavailable" notification
 *     (patient safety beats UI cleanliness — clinicians need to know).
 */
import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { usePatient } from "@openmrs/esm-framework";
import DrugAllergyAlert from "./drug-allergy-alert.extension";
import { useDrugSnomed } from "../api/medication.resource";
import { useDrugAllergyAlerts, CdsHooksCard } from "../api/cds-hooks.resource";

vi.mock("../api/medication.resource");
vi.mock("../api/cds-hooks.resource");

const mockedUsePatient = vi.mocked(usePatient);
const mockedUseDrugSnomed = vi.mocked(useDrugSnomed);
const mockedUseDrugAllergyAlerts = vi.mocked(useDrugAllergyAlerts);

const drugUuid = "amoxicillin-uuid";

const criticalCard: CdsHooksCard = {
  uuid: "card-1",
  summary: "⚠ Allergy to penicillin (class match)",
  detail:
    "Amoxicillin contains Amoxicillin (substance), which is a Penicillin — the causative-agent class for Allergy to penicillin.\n\nRecorded reaction: Hepatic toxicity",
  indicator: "critical",
  source: { label: "OpenMRS Drug-Allergy Alert" },
  links: [],
};

describe("DrugAllergyAlert", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedUsePatient.mockReturnValue({
      patient: { id: "patient-uuid" } as never,
      isLoading: false,
    } as never);
  });

  it("renders nothing when the drug has no SNOMED mapping", () => {
    mockedUseDrugSnomed.mockReturnValue({
      info: { snomedCode: null, display: "Acetaminophen" },
      isLoading: false,
      error: undefined,
    } as never);
    mockedUseDrugAllergyAlerts.mockReturnValue({
      cards: [],
      isLoading: false,
      error: undefined,
      refresh: vi.fn(),
    } as never);

    const { container } = render(<DrugAllergyAlert orderItemUuid={drugUuid} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the backend returns no cards", () => {
    mockedUseDrugSnomed.mockReturnValue({
      info: { snomedCode: "27658006", display: "Amoxicillin" },
      isLoading: false,
      error: undefined,
    } as never);
    mockedUseDrugAllergyAlerts.mockReturnValue({
      cards: [],
      isLoading: false,
      error: undefined,
      refresh: vi.fn(),
    } as never);

    const { container } = render(<DrugAllergyAlert orderItemUuid={drugUuid} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a critical InlineNotification when the backend returns a critical card", () => {
    mockedUseDrugSnomed.mockReturnValue({
      info: { snomedCode: "27658006", display: "Amoxicillin" },
      isLoading: false,
      error: undefined,
    } as never);
    mockedUseDrugAllergyAlerts.mockReturnValue({
      cards: [criticalCard],
      isLoading: false,
      error: undefined,
      refresh: vi.fn(),
    } as never);

    render(<DrugAllergyAlert orderItemUuid={drugUuid} />);

    // Carbon's InlineNotification renders with role="status".
    const alert = screen.getByRole("status");
    expect(alert).toBeInTheDocument();
    expect(alert).toHaveTextContent(/Allergy to penicillin/);
    expect(alert).toHaveTextContent(/Amoxicillin contains Amoxicillin/);
    expect(alert).toHaveTextContent(/Recorded reaction: Hepatic toxicity/);
  });

  it("renders a visible 'unavailable' notice on backend error", () => {
    mockedUseDrugSnomed.mockReturnValue({
      info: { snomedCode: "27658006", display: "Amoxicillin" },
      isLoading: false,
      error: undefined,
    } as never);
    mockedUseDrugAllergyAlerts.mockReturnValue({
      cards: [],
      isLoading: false,
      error: new Error("CDS-Hooks endpoint unreachable"),
      refresh: vi.fn(),
    } as never);

    render(<DrugAllergyAlert orderItemUuid={drugUuid} />);
    expect(screen.getByText(/Allergy check unavailable/)).toBeInTheDocument();
  });

  it("renders nothing while data is loading", () => {
    mockedUseDrugSnomed.mockReturnValue({
      info: null,
      isLoading: true,
      error: undefined,
    } as never);
    mockedUseDrugAllergyAlerts.mockReturnValue({
      cards: [],
      isLoading: false,
      error: undefined,
      refresh: vi.fn(),
    } as never);

    const { container } = render(<DrugAllergyAlert orderItemUuid={drugUuid} />);
    expect(container).toBeEmptyDOMElement();
  });
});
