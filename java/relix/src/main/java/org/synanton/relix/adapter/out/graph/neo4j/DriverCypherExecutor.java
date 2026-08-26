package org.synanton.relix.adapter.out.graph.neo4j;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DriverCypherExecutor implements CypherExecutor {

    private final Driver driver;
    private final String database;

    public DriverCypherExecutor(Driver driver, String database) {
        this.driver = driver;
        this.database = database == null || database.isBlank() ? "neo4j" : database;
    }

    @Override
    public List<Map<String, Object>> read(String cypher, Map<String, Object> params) {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Record record : tx.run(cypher, Values.value(params)).list()) {
                    rows.add(toMap(record));
                }
                return rows;
            });
        }
    }

    @Override
    public void write(String cypher, Map<String, Object> params) {
        try (Session session = driver.session(SessionConfig.forDatabase(database))) {
            session.executeWrite(tx -> {
                tx.run(cypher, Values.value(params)).consume();
                return null;
            });
        }
    }

    private static Map<String, Object> toMap(Record record) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String key : record.keys()) {
            row.put(key, convert(record.get(key)));
        }
        return row;
    }

    private static Object convert(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.type().name().equals("LIST")) {
            List<Object> items = new ArrayList<>();
            for (Value item : value.values()) {
                items.add(convert(item));
            }
            return items;
        }
        if (value.type().name().equals("MAP")) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.keys()) {
                map.put(key, convert(value.get(key)));
            }
            return map;
        }
        return value.asObject();
    }
}
