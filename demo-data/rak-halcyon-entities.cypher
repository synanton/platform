MERGE (halcyon:Entity {tenant: 'local-real-demo', id: 'entity-halcyon-7'})
  SET halcyon.label = 'Halcyon-7', halcyon.type = 'MaintenanceSystem', halcyon.confidence = 1.0, halcyon.sources = ''
MERGE (loop:Entity {tenant: 'local-real-demo', id: 'entity-auxiliary-coolant-loop'})
  SET loop.label = 'auxiliary coolant loop', loop.type = 'Component', loop.confidence = 1.0, loop.sources = ''
MERGE (valve:Entity {tenant: 'local-real-demo', id: 'entity-relief-valve'})
  SET valve.label = 'relief valve', valve.type = 'Component', valve.confidence = 1.0, valve.sources = ''
MERGE (manifold:Entity {tenant: 'local-real-demo', id: 'entity-secondary-manifold'})
  SET manifold.label = 'secondary manifold', manifold.type = 'Component', manifold.confidence = 1.0, manifold.sources = ''
MERGE (log:Entity {tenant: 'local-real-demo', id: 'entity-pressure-test-log'})
  SET log.label = 'pressure-test log', log.type = 'Document', log.confidence = 1.0, log.sources = ''
MERGE (dew:Entity {tenant: 'local-real-demo', id: 'entity-ambient-dew-point'})
  SET dew.label = 'ambient dew point', dew.type = 'Measurement', dew.confidence = 1.0, dew.sources = ''
MERGE (ninety:Entity {tenant: 'local-real-demo', id: 'entity-ninety-days'})
  SET ninety.label = 'ninety days', ninety.type = 'Time', ninety.confidence = 1.0, ninety.sources = ''
MERGE (seventy:Entity {tenant: 'local-real-demo', id: 'entity-two-hundred-seventy-days'})
  SET seventy.label = 'two hundred seventy days', seventy.type = 'Time', seventy.confidence = 1.0, seventy.sources = ''
MERGE (test:Entity {tenant: 'local-real-demo', id: 'entity-every-test'})
  SET test.label = 'every test', test.type = 'Concept', test.confidence = 1.0, test.sources = ''
