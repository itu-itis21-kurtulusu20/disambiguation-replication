package com.example.llmdyn.runtime;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class BeanRegistrar {
    private final GenericApplicationContext applicationContext;
    private final List<Class<?>> registeredEntities = new ArrayList<>();
    private final Set<Class<?>> registeredClasses = new HashSet<>();
    private final List<Class<?>> registeredNonBeans = new ArrayList<>();
    private jakarta.persistence.EntityManagerFactory currentEmf = null;

    public BeanRegistrar(GenericApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void registerBean(Class<?> clazz, String beanName) {
        if (Exception.class.isAssignableFrom(clazz) || RuntimeException.class.isAssignableFrom(clazz)) {
            System.out.println("⚠️ Skipping exception: " + clazz.getSimpleName());
            return;
        }

        if (clazz.isAnnotationPresent(Entity.class)) {
            System.out.println("✅ Registering entity: " + clazz.getSimpleName());
            addEntityToManagerFactory(clazz);
            registeredClasses.add(clazz);
            // Immediately recreate EMF so Hibernate creates/updates the schema for this entity
            recreateEntityManagerFactory();
        } else if (JpaRepository.class.isAssignableFrom(clazz)) {
            System.out.println("✅ Registering repository: " + clazz.getSimpleName());
            registerJpaRepository(clazz, beanName);
            registeredClasses.add(clazz);
        } else if (clazz.isAnnotationPresent(Service.class)) {
            System.out.println("✅ Registering service: " + clazz.getSimpleName());
            registerService(clazz, beanName);
            registeredClasses.add(clazz);
        } else if (clazz.isAnnotationPresent(RestController.class) || clazz.isAnnotationPresent(Controller.class)) {
            System.out.println("✅ Registering controller: " + clazz.getSimpleName());
            registerController(clazz, beanName);
            registeredClasses.add(clazz);
        } else if (clazz.isAnnotationPresent(Component.class)) {
            System.out.println("✅ Registering component: " + clazz.getSimpleName());
            registerGenericBean(clazz, beanName);
            registeredClasses.add(clazz);
        } else {
            System.out.println("✅ Tracking non-bean class: " + clazz.getSimpleName());
            registeredNonBeans.add(clazz);
        }
    }

    private void addEntityToManagerFactory(Class<?> entityClass) {
        if (!registeredEntities.contains(entityClass)) {
            registeredEntities.add(entityClass);
        }
    }

    public void recreateEntityManagerFactory() {
        if (registeredEntities.isEmpty()) {
            System.out.println("⚠️ No entities to register, skipping EMF recreation");
            return;
        }
        System.out.println("Recreating EntityManagerFactory with " + registeredEntities.size() + " entities");

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        Set<ClassLoader> classLoaders = new HashSet<>();
        for (Class<?> entityClass : registeredEntities) {
            classLoaders.add(entityClass.getClassLoader());
        }
        classLoaders.add(this.getClass().getClassLoader());

        MultiClassLoader multiClassLoader = new MultiClassLoader(new ArrayList<>(classLoaders));
        Thread.currentThread().setContextClassLoader(multiClassLoader);

        try {
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getBeanFactory();

            if (currentEmf != null && currentEmf.isOpen()) {
                System.out.println("Closing previous EntityManagerFactory");
                currentEmf.close();
            }

            if (beanFactory.containsSingleton("dynamicEntityManagerFactory")) {
                beanFactory.destroySingleton("dynamicEntityManagerFactory");
            }
            if (beanFactory.containsBeanDefinition("dynamicEntityManagerFactory")) {
                beanFactory.removeBeanDefinition("dynamicEntityManagerFactory");
            }

            org.hibernate.cfg.Configuration configuration = new org.hibernate.cfg.Configuration();

            for (Class<?> entityClass : registeredEntities) {
                try {
                    System.out.println("Adding entity to fresh configuration: " + entityClass.getName());
                    configuration.addAnnotatedClass(entityClass);
                } catch (Exception e) {
                    System.err.println("Failed to add entity class: " + entityClass.getName() + ", skipping: " + e.getMessage());
                }
            }

            org.springframework.core.env.Environment env = applicationContext.getEnvironment();

            configuration.setProperty("hibernate.hbm2ddl.auto",
                    env.getProperty("spring.jpa.hibernate.ddl-auto", "update"));

            String dialect = env.getProperty("spring.jpa.properties.hibernate.dialect");
            if (dialect != null) {
                configuration.setProperty("hibernate.dialect", dialect);
            }

            configuration.setProperty("hibernate.globally_quoted_identifiers", "true");
            configuration.setProperty("hibernate.show_sql",
                    env.getProperty("spring.jpa.show-sql", "false"));
            configuration.setProperty("hibernate.format_sql",
                    env.getProperty("spring.jpa.properties.hibernate.format_sql", "false"));

            javax.sql.DataSource dataSource = applicationContext.getBean(javax.sql.DataSource.class);

            org.hibernate.boot.registry.StandardServiceRegistryBuilder registryBuilder =
                    new org.hibernate.boot.registry.StandardServiceRegistryBuilder(
                            new org.hibernate.boot.registry.BootstrapServiceRegistryBuilder()
                                    .applyClassLoader(multiClassLoader)
                                    .build()
                    )
                            .applySettings(configuration.getProperties())
                            .applySetting("hibernate.connection.datasource", dataSource);

            org.hibernate.service.ServiceRegistry serviceRegistry = registryBuilder.build();
            org.hibernate.SessionFactory sessionFactory = configuration.buildSessionFactory(serviceRegistry);

            // Ensure mapped tables are created/updated immediately before repositories execute.
            try {
                sessionFactory.getSchemaManager().exportMappedObjects(true);
                System.out.println("✅ Hibernate schema export completed for dynamic entities");
            } catch (Exception schemaEx) {
                System.err.println("⚠️ Could not force schema export: " + schemaEx.getMessage());
            }

            jakarta.persistence.EntityManagerFactory emf = sessionFactory.unwrap(jakarta.persistence.EntityManagerFactory.class);

            currentEmf = emf;

            beanFactory.registerSingleton("dynamicEntityManagerFactory", emf);

            try {
                JpaTransactionManager transactionManager = applicationContext.getBean(JpaTransactionManager.class);
                transactionManager.setEntityManagerFactory(emf);
                System.out.println("✅ JpaTransactionManager updated with new EntityManagerFactory");
            } catch (Exception e) {
                System.err.println("⚠️ Could not update JpaTransactionManager: " + e.getMessage());
            }

            System.out.println("✅ Dynamic EntityManagerFactory recreated with " + registeredEntities.size() + " entities");

        } catch (Exception e) {
            System.err.println("Failed to recreate dynamic EntityManagerFactory: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to recreate dynamic EntityManagerFactory", e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void registerJpaRepository(Class<?> repositoryInterface, String beanName) {
        System.out.println("Registering JPA repository: " + beanName);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getBeanFactory();

            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.removeBeanDefinition(beanName);
            }
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName);
            }

            ClassLoader dynamicClassLoader = repositoryInterface.getClassLoader();
            Thread.currentThread().setContextClassLoader(dynamicClassLoader);

            BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .genericBeanDefinition(JpaRepositoryFactoryBean.class);

            builder.addConstructorArgValue(repositoryInterface);
            builder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
            builder.setLazyInit(false);

            AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            beanDefinition.setBeanClassName(JpaRepositoryFactoryBean.class.getName());

            beanFactory.registerBeanDefinition(beanName, beanDefinition);
            beanFactory.setBeanClassLoader(dynamicClassLoader);

            Object repository = applicationContext.getBean(beanName);

            System.out.println("Repository registered successfully: " + beanName + " - " + repository.getClass().getName());

        } catch (Exception e) {
            System.err.println("Failed to register repository: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to register repository: " + beanName, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void registerService(Class<?> serviceClass, String beanName) {
        System.out.println("Registering service: " + beanName);
        registerGenericBean(serviceClass, beanName);
    }

    private void registerController(Class<?> controllerClass, String beanName) {
        System.out.println("Registering controller: " + beanName);
        registerGenericBean(controllerClass, beanName);
    }

    private void registerGenericBean(Class<?> beanClass, String beanName) {
        System.out.println("Registering generic bean: " + beanName);

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getBeanFactory();

            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.removeBeanDefinition(beanName);
            }
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName);
            }

            ClassLoader beanClassLoader = beanClass.getClassLoader();

            if (beanClassLoader != null && !beanClassLoader.equals(this.getClass().getClassLoader())) {
                Thread.currentThread().setContextClassLoader(beanClassLoader);
            }

            BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .genericBeanDefinition(beanClass)
                    .setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);

            AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            beanDefinition.setSynthetic(true);

            beanFactory.registerBeanDefinition(beanName, beanDefinition);

            Object bean = applicationContext.getBean(beanName);

            System.out.println("Bean registered successfully: " + beanName + " - " + bean.getClass().getName());
        } catch (Exception e) {
            System.err.println("Failed to register bean: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to register bean: " + beanName, e);
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    public String getTableName(Class<?> entityClass) {
        if (entityClass.isAnnotationPresent(Table.class)) {
            Table table = entityClass.getAnnotation(Table.class);
            if (!table.name().isEmpty()) {
                return table.name();
            }
        }
        return entityClass.getSimpleName().toLowerCase();
    }

    public void clearEntities() {
        System.out.println("🧹 Clearing " + registeredEntities.size() + " registered entities");

        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getBeanFactory();

        for (Class<?> clazz : new ArrayList<>(registeredClasses)) {
            String beanName = getBeanName(clazz);
            if (beanFactory.containsBeanDefinition(beanName)) {
                beanFactory.removeBeanDefinition(beanName);
            }
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName);
            }
        }

        registeredEntities.clear();
        registeredClasses.clear();
        registeredNonBeans.clear();
    }

    private String getBeanName(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    public List<Class<?>> getRegisteredEntities() {
        return new ArrayList<>(registeredEntities);
    }

    public List<Class<?>> getRegisteredNonBeans() {
        return new ArrayList<>(registeredNonBeans);
    }

    private static class MultiClassLoader extends ClassLoader {
        private final List<ClassLoader> classLoaders;

        public MultiClassLoader(List<ClassLoader> classLoaders) {
            this.classLoaders = classLoaders;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            for (ClassLoader cl : classLoaders) {
                try {
                    return cl.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // Continue to next
                }
            }
            throw new ClassNotFoundException(name);
        }
    }
}