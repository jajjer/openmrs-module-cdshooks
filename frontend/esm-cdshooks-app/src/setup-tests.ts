import "@testing-library/jest-dom/vitest";
import { vi } from "vitest";

// Stub @openmrs/esm-framework globally — tests opt in to specific behaviors.
vi.mock("@openmrs/esm-framework", () => ({
  openmrsFetch: vi.fn(),
  fhirBaseUrl: "/openmrs/ws/fhir2/R4",
  usePatient: vi.fn(() => ({ patient: null, isLoading: false })),
  getAsyncLifecycle: vi.fn(),
  defineConfigSchema: vi.fn(),
}));

vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string, fallback?: string) => fallback ?? key }),
}));
