package com.example.llmdyn.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.stereotype.Component;

@Component
public class RuntimeEntityRegistry {

    private final EntityManagerFactory entityManagerFactory;

    public RuntimeEntityRegistry(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    public void registerEntity(Class<?> entityClass) {
        SessionFactoryImplementor sessionFactory =
                entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        // Create new metadata with the entity
        StandardServiceRegistry serviceRegistry =
                new StandardServiceRegistryBuilder()
                        .applySettings(sessionFactory.getProperties())
                        .build();

        MetadataSources metadataSources = new MetadataSources(serviceRegistry);
        metadataSources.addAnnotatedClass(entityClass);

        Metadata metadata = metadataSources.buildMetadata();

        // This forces Hibernate to create the table if ddl-auto is update
        sessionFactory.getSchemaManager().exportMappedObjects(true);
    }
}