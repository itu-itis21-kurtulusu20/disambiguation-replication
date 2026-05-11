// src/main/java/com/example/llmdyn/service/DatabaseCleanupService.java
package com.example.llmdyn.service;

import com.example.llmdyn.runtime.BeanRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DatabaseCleanupService {

    @Autowired
    private BeanRegistrar beanRegistrar;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleanupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void cleanDatabase() {
        List<Class<?>> entities = beanRegistrar.getRegisteredEntities();
        if (!entities.isEmpty()) {
            Set<String> tables = new LinkedHashSet<>();
            for (Class<?> entityClass : entities) {
                tables.add(beanRegistrar.getTableName(entityClass));
            }
            truncateTables(tables, "registered entities");
            return;
        }

        // Fallback keeps cleanup deterministic even when entity registry was already cleared.
        List<String> publicTables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class
        );
        Set<String> filtered = publicTables.stream()
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> !name.equalsIgnoreCase("flyway_schema_history"))
                .filter(name -> !name.equalsIgnoreCase("databasechangelog"))
                .filter(name -> !name.equalsIgnoreCase("databasechangeloglock"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (filtered.isEmpty()) {
            System.out.println("No public tables found to clean");
            return;
        }

        truncateTables(filtered, "public schema fallback");
    }

    public void clearAllTables() {
        cleanDatabase();
        beanRegistrar.clearEntities();
        beanRegistrar.recreateEntityManagerFactory();
        System.out.println("Database and entity registry cleared successfully");
    }

    public void dropAllPublicTables() {
        List<String> publicTables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class
        );

        if (publicTables.isEmpty()) {
            System.out.println("No public tables found to drop");
            return;
        }

        for (String tableName : publicTables) {
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            String sql = "DROP TABLE IF EXISTS " + quoteIdentifier(tableName) + " CASCADE";
            jdbcTemplate.execute(sql);
        }

        beanRegistrar.clearEntities();
        beanRegistrar.recreateEntityManagerFactory();
        System.out.println("All public tables dropped and entity registry reset");
    }

    public void truncateTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return;
        }
        truncateTables(Set.of(tableName.trim()), "single table request");
    }

    private void truncateTables(Set<String> tableNames, String source) {
        if (tableNames == null || tableNames.isEmpty()) {
            return;
        }

        String joinedTables = tableNames.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        String sql = "TRUNCATE TABLE " + joinedTables + " RESTART IDENTITY CASCADE";
        try {
            jdbcTemplate.execute(sql);
            System.out.println("Database cleanup succeeded from " + source + ": " + joinedTables);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to truncate tables from " + source + ": " + joinedTables, e);
        }
    }

    private String quoteIdentifier(String identifier) {
        String safe = identifier == null ? "" : identifier.trim().replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }
}