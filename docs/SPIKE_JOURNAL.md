# Spike Journal — what we proved and how

This module started as a spike validating a CDS-Hooks-based architecture for
the OpenMRS Talk thread *"1:1 Allergy/Rx should trigger warning"*. As of the
commit that introduced this file, the spike was end-to-end working against a
real OpenMRS 2.8.4 backend.

## What's confirmed working

```bash
# 1. Build
cd openmrs-module-cdshooks && mvn clean install
# -> 8 unit tests pass; omod/target/cdshooks-omod-1.0.0-SNAPSHOT.omod produced

# 2. Bring up isolated OpenMRS RefApp stack
docker compose -p cdshooks-dev -f docs/spike/cdshooks-dev-docker-compose.yml up -d

# 3. Deploy module
docker cp omod/target/cdshooks-omod-1.0.0-SNAPSHOT.omod \
  cdshooks-dev-backend-1:/openmrs/data/modules/cdshooks-1.0.0-SNAPSHOT.omod
docker restart cdshooks-dev-backend-1

# 4. Discovery endpoint
curl -u admin:Admin123 http://localhost:8081/openmrs/ms/cdsServicesServlet
# {"services":[{"hook":"medication-prescribe","title":"Drug-Allergy Alert",
#   "description":"...","id":"drug-allergy"}]}
```

## Architectural surprises we found in the spike

Captured in detail in `docs/spike/`:

- **SNOMED has parallel hierarchies** (substance vs. medicinal product vs. finding). Cross-hierarchy `$subsumes` returns `not-subsumed`. The real algorithm has to bridge via SNOMED attribute relationships — `Causative agent` (246075003) on findings and `Has active ingredient` (127489000) on products. See `docs/spike/05-snomed-data-check.md`.
- **CIEL data coverage is partial.** The flagship "Amoxicillin" concept on the running RefApp has zero SNOMED mappings. Phase 2 of the design needs CIEL-team coordination, not just an Initializer CSV in the distro.
- **`ConceptTranslator` already emits SNOMED Codings.** When a CIEL concept *is* mapped to SNOMED, it surfaces automatically through `AllergyIntolerance.code` and `Medication.code` in the existing FHIR2 module. No translator work needed. See `docs/spike/04-allergy-inventory.md`.
- **No drug-allergy CDS exists in the RefApp today.** Drools rules-engine module and Bahmni's CDS-Hooks module are adjacent but don't cover drug-allergy. The new module is genuinely net-new.

## Module-internals gotchas (so the next contributor doesn't lose hours)

- **`@PostConstruct` calling `Context.getAdministrationService()` will deadlock module startup.** OpenMRS's service context isn't guaranteed initialized when module beans are being wired. Read global properties lazily on first use, not eagerly during bean creation. See `SnowstormClient.gp(...)`.
- **`config.xml` element order matters and the OpenMRS DTD enforces it.** `globalProperty` must come *before* `servlet`. A misordered `<servlet>` element is silently dropped — no error, no warning, just no servlet registered.
- **Spring `@Controller` doesn't work for `/ws/*` URLs.** That prefix is owned by the legacy REST module's DispatcherServlet. Module custom endpoints register a `<servlet>` in config.xml; OpenMRS dispatches via `ModuleServlet` at `/openmrs/ms/{servletName}`. To get a custom URL prefix like `/ws/cds-services`, register a forwarding filter (the fhir2 module pattern).
- **When `ModuleServlet` dispatches to a module servlet, the request's `pathInfo` includes the servlet name** (e.g., `/cdsServicesServlet/...`). The servlet doesn't get a URL-rewritten request. Account for this in path parsing.

## Known follow-ups

- **CSRF filter intercepts POST.** Discovery (GET) works; POST returns 302 to `/openmrs/error.html`. fhir2's pattern is to declare a filter mapping in config.xml exempting the servlet's URL. Not yet implemented here.
- **`/ws/cds-services` (spec-compliant URL).** Currently the endpoint is at `/openmrs/ms/cdsServicesServlet`. A forwarding filter à la fhir2's `ForwardingFilter` would expose the spec-compliant URL.
- **Tests for `CdsHooksServiceImpl`, `SnomedMappingExtractor`, `SeverityMapper`.** Currently only `AllergyMatcherImpl` and `CdsHooksRequestParser` have unit tests.
- **Frontend extension package** (`esm-cdshooks-app` or similar). Registers into `order-item-additional-info-slot` in `esm-patient-chart`, calls the endpoint, renders Carbon `<InlineNotification kind="warning">`. See `docs/spike/03-esm-patient-chart-findings.md` for the exact extension slot and data contract.

## Status

**Pre-community-review.** The design proposal in `docs/DESIGN.md` has not yet been posted to OpenMRS Talk for feedback. The implementation here is a spike that validates the architecture; the final shape of a contributed module may differ based on community input on:

- Hosting/packaging (new module vs. extending an existing one)
- The spec-compliant URL pattern story (servlet vs. forwarding filter)
- Which SNOMED reference frame is canonical for CIEL drug/allergen concepts
- Whether to align with Bahmni's existing `openmrs-module-cdss` rather than build separately
