# Implementation Notes

Engineering notes for contributors working on this module: how to run it
locally, the SNOMED modeling realities the matching algorithm has to deal with,
and the OpenMRS-platform gotchas that cost real hours to discover. The full
design rationale lives in [`DESIGN.md`](DESIGN.md).

## Running it locally

```bash
# 1. Build (unit tests run as part of the build)
mvn clean install
# -> omod/target/cdshooks-omod-1.0.0-SNAPSHOT.omod produced

# 2. Bring up an isolated OpenMRS RefApp stack
docker compose -p cdshooks-dev -f dev/docker-compose.yml up -d

# 3. Deploy the module
docker cp omod/target/cdshooks-omod-1.0.0-SNAPSHOT.omod \
  cdshooks-dev-backend-1:/openmrs/data/modules/cdshooks-1.0.0-SNAPSHOT.omod
docker restart cdshooks-dev-backend-1

# 4. Discovery endpoint (spec-compliant CDS-Hooks URL)
curl -u admin:Admin123 http://localhost:8081/openmrs/ws/cds-services
# {"services":[{"hook":"medication-prescribe","title":"Drug-Allergy Alert",
#   "description":"...","id":"drug-allergy"}]}
```

A local Snowstorm terminology server (for offline or write access) is available
via `dev/docker-compose.snowstorm.yml` — see that file's header for the SNOMED
release-loading steps.

## SNOMED modeling findings

These shaped the matching algorithm and are worth understanding before touching
`AllergyMatcherImpl`:

- **SNOMED has parallel hierarchies** — substance, medicinal product, and
  clinical finding are separate trees. Allergen concepts map to *findings*
  (e.g., "Allergy to penicillin (finding)" `91936005`); drug concepts map to
  *medicinal products* (e.g., "Acetaminophen-containing product" `777067000`).
  A direct `$subsumes` across these trees returns `not-subsumed`. The algorithm
  bridges them via SNOMED attribute relationships — `Causative agent`
  (`246075003`) on findings and `Has active ingredient` (`127489000`) on
  products — then does substance-vs-substance `$subsumes`.
- **CIEL data coverage is partial.** On the running RefApp, the flagship
  "Amoxicillin" concept (CIEL `71160`) carries zero SNOMED mappings, while
  "Acetaminophen" (CIEL `70116`) is richly mapped. Back-filling SNOMED mappings
  on common drug concepts is CIEL-team coordination work, not something an
  Initializer CSV in the distro solves on its own. This is the long pole in
  Phase 2 of the design.
- **`ConceptTranslator` already emits SNOMED Codings.** When a CIEL concept *is*
  mapped to SNOMED, it surfaces automatically through `AllergyIntolerance.code`
  and `Medication.code` in the existing FHIR2 module. No translator work needed.
- **No drug-allergy CDS exists in the RefApp today.** The Drools rules-engine
  module and Bahmni's CDS-Hooks module are adjacent but don't cover
  drug-allergy. This module is genuinely net-new.

## OpenMRS-platform gotchas

So the next contributor doesn't lose hours:

- **`@PostConstruct` calling `Context.getAdministrationService()` will deadlock
  module startup.** OpenMRS's service context isn't guaranteed initialized when
  module beans are being wired. Read global properties lazily on first use, not
  eagerly during bean creation. See `SnowstormClient.gp(...)`.
- **`config.xml` element order matters and the OpenMRS DTD enforces it.**
  `globalProperty` must come *before* `servlet`. A misordered `<servlet>`
  element is silently dropped — no error, no warning, just no servlet
  registered.
- **Spring `@Controller` doesn't work for `/ws/*` URLs.** That prefix is owned by
  the legacy REST module's DispatcherServlet. Module custom endpoints register a
  `<servlet>` in config.xml; OpenMRS dispatches via `ModuleServlet` at
  `/openmrs/ms/{servletName}`. To get a spec-compliant URL prefix like
  `/ws/cds-services`, register a forwarding filter (the fhir2 module pattern) —
  see `ForwardingFilter`, which also marks the path CSRF-exempt.
- **When `ModuleServlet` dispatches to a module servlet, the request's
  `pathInfo` includes the servlet name** (e.g., `/cdsServicesServlet/...`). The
  servlet doesn't get a URL-rewritten request. Account for this in path parsing.

## Auth model

The public endpoint is at `/openmrs/ws/cds-services`, CSRF-exempt and reachable
via `ForwardingFilter`. The filter verifies an HS256 bearer token (per the
CDS-Hooks 2.0 security spec) when an HMAC secret is configured; with no secret
set, bearer auth is disabled and clients fall back to session-cookie or basic
auth. Note that basic auth alone does not establish a privileged user context
for the POST invocation path — `CdsHooksServiceImpl` grants itself the minimum
read privileges it needs for the lookup.

## Status

**Pre-review.** The design proposal in [`DESIGN.md`](DESIGN.md) has not yet been
reviewed by OpenMRS maintainers. The implementation here validates the
architecture end-to-end; the final shape of a contributed module may shift based
on maintainer feedback on:

- Hosting/packaging (new module vs. extending an existing one)
- Which SNOMED reference frame is canonical for CIEL drug/allergen concepts
  (finding vs. substance vs. product)
- Whether to align with Bahmni's existing `openmrs-module-cdss` rather than
  build separately
