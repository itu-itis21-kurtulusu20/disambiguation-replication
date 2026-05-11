package com.example.llmdyn.service;

import com.example.llmdyn.model.ErrorCode;
import com.example.llmdyn.model.FailureDetail;
import com.example.llmdyn.model.FailureLevel;
import com.example.llmdyn.runtime.DynamicCompilationException;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.jpa.JpaSystemException;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.Locale;

public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    public static FailureDetail classify(String stage, Throwable throwable, String message) {
        String safeMessage = message == null ? "" : message;
        Throwable rootCause = findRootCause(throwable);
        String rootCauseMessage = rootCause == null ? null : rootCause.getMessage();
        String normalized = normalize(safeMessage + " " + (rootCauseMessage == null ? "" : rootCauseMessage));
        ErrorCode errorCode = classifyCode(stage, throwable, normalized);
        FailureLevel level = errorCode.getLevel();
        String exceptionType = throwable == null ? null : throwable.getClass().getSimpleName();
        String rootCauseType = rootCause == null ? null : rootCause.getClass().getSimpleName();
        return new FailureDetail(
                level,
                errorCode,
                stage,
                safeMessage,
                exceptionType,
                rootCauseType,
                rootCauseMessage,
                errorCode.name()
        );
    }

    public static FailureDetail functionalFailure(String stage, String message) {
        return new FailureDetail(
                FailureLevel.LEVEL_5_FUNCTIONAL_MERGED,
                ErrorCode.EXEC_ASSERTION_MISMATCH,
                stage,
                message,
                null,
                null,
                null,
                ErrorCode.EXEC_ASSERTION_MISMATCH.name()
        );
    }

    private static ErrorCode classifyCode(String stage, Throwable throwable, String normalizedMessage) {
        if (hasCause(throwable, DynamicCompilationException.class) || "compilation".equals(stage) || looksLikeCompilation(normalizedMessage)) {
            return classifyCompilationCode(normalizedMessage);
        }

        if (hasCause(throwable, UnsatisfiedDependencyException.class) || normalizedMessage.contains("unsatisfieddependencyexception")) {
            return ErrorCode.CTX_UNSATISFIED_DEPENDENCY;
        }
        if (hasCause(throwable, NoSuchBeanDefinitionException.class) || normalizedMessage.contains("nosuchbeandefinitionexception")) {
            return ErrorCode.CTX_NO_SUCH_BEAN;
        }
        if (hasCause(throwable, BeanCreationException.class)
                || looksLikeSpringContext(normalizedMessage)) {
            return ErrorCode.CTX_BEAN_CREATION;
        }

        if (hasCause(throwable, PersistenceException.class)
                || hasCause(throwable, DataAccessException.class)
                || hasCause(throwable, JpaSystemException.class)
                || hasCause(throwable, SQLException.class)
                || looksLikePersistence(normalizedMessage)) {
            return classifyPersistenceCode(throwable, normalizedMessage);
        }

        if (hasCause(throwable, NoSuchMethodException.class) || normalizedMessage.contains("nosuchmethodexception") || normalizedMessage.contains("method not found")) {
            return ErrorCode.EXEC_NO_SUCH_METHOD;
        }
        if (hasCause(throwable, ClassNotFoundException.class) || normalizedMessage.contains("classnotfoundexception")) {
            return ErrorCode.EXEC_CLASS_NOT_FOUND;
        }
        if (hasCause(throwable, InvocationTargetException.class)) {
            return ErrorCode.EXEC_INVOCATION_TARGET;
        }
        if (hasCause(throwable, IllegalArgumentException.class) || normalizedMessage.contains("illegalargumentexception")) {
            return ErrorCode.EXEC_ILLEGAL_ARGUMENT;
        }

        if ("generation".equals(stage) || "extraction".equals(stage)) {
            return classifyGenerationCode(normalizedMessage);
        }

        if ("execution".equals(stage) || "runner".equals(stage)) {
            return ErrorCode.EXEC_UNKNOWN;
        }

        if (normalizedMessage.contains("applicationcontext") || normalizedMessage.contains("beandefinitionstoreexception")) {
            return ErrorCode.CTX_CONTEXT_BOOTSTRAP;
        }

        return ErrorCode.UNCLASSIFIED;
    }

    private static ErrorCode classifyCompilationCode(String normalizedMessage) {
        if (normalizedMessage.contains("cannot find symbol")) {
            return ErrorCode.CMP_CANNOT_FIND_SYMBOL;
        }
        if (normalizedMessage.contains("package") && normalizedMessage.contains("does not exist")) {
            return ErrorCode.CMP_PACKAGE_NOT_FOUND;
        }
        if (normalizedMessage.contains("incompatible types")) {
            return ErrorCode.CMP_TYPE_MISMATCH;
        }
        if (normalizedMessage.contains("';' expected")
                || normalizedMessage.contains("reached end of file while parsing")
                || normalizedMessage.contains("not a statement")) {
            return ErrorCode.CMP_SYNTAX_ERROR;
        }
        return ErrorCode.CMP_COMPILATION_FAILED;
    }

    private static ErrorCode classifyPersistenceCode(Throwable throwable, String normalizedMessage) {
        if (normalizedMessage.contains("entitymanagerfactory is closed")
                || normalizedMessage.contains("sessionfactoryimpl.validatenotclosed")) {
            return ErrorCode.DB_PERSISTENCE_EXCEPTION;
        }
        if (normalizedMessage.contains("unable to rollback against jdbc connection")
                || normalizedMessage.contains("transactionexception")
                || normalizedMessage.contains("connection is closed")) {
            return ErrorCode.DB_PERSISTENCE_EXCEPTION;
        }
        if (looksLikeHibernateNumericScaleMapping(normalizedMessage)) {
            return ErrorCode.DB_SCHEMA_MISMATCH;
        }
        if (hasCause(throwable, SQLException.class) || normalizedMessage.contains("sqlexception")) {
            return ErrorCode.DB_SQL_EXCEPTION;
        }
        if (hasCause(throwable, DataAccessException.class) || normalizedMessage.contains("dataaccessexception")) {
            return ErrorCode.DB_DATA_ACCESS_EXCEPTION;
        }
        if (normalizedMessage.contains("constraint") || normalizedMessage.contains("unique") || normalizedMessage.contains("violates")) {
            return ErrorCode.DB_CONSTRAINT_VIOLATION;
        }
        if (normalizedMessage.contains("column") || normalizedMessage.contains("relation") || normalizedMessage.contains("table") || normalizedMessage.contains("ddl")) {
            return ErrorCode.DB_SCHEMA_MISMATCH;
        }
        return ErrorCode.DB_PERSISTENCE_EXCEPTION;
    }

    private static ErrorCode classifyGenerationCode(String normalizedMessage) {
        if (normalizedMessage.isBlank()) {
            return ErrorCode.GEN_OUTPUT_EMPTY;
        }
        if (normalizedMessage.contains("api error")
                || normalizedMessage.contains("status 401")
                || normalizedMessage.contains("status 403")
                || normalizedMessage.contains("status 429")
                || normalizedMessage.contains("openai")
                || normalizedMessage.contains("gemini")) {
            return ErrorCode.GEN_LLM_API_ERROR;
        }
        if (normalizedMessage.contains("missing candidates")
                || normalizedMessage.contains("missing choices")
                || normalizedMessage.contains("invalid") && normalizedMessage.contains("response")) {
            return ErrorCode.GEN_PROVIDER_RESPONSE_INVALID;
        }
        return ErrorCode.GEN_UNKNOWN;
    }

    private static boolean looksLikeCompilation(String message) {
        String msg = normalize(message);
        return msg.contains("compilation failed")
                || msg.contains("cannot find symbol")
                || msg.contains("package does not exist")
                || msg.contains("incompatible types");
    }

    private static boolean looksLikeSpringContext(String message) {
        String msg = normalize(message);
        return msg.contains("beancreationexception")
                || msg.contains("unsatisfieddependencyexception")
                || msg.contains("beandefinitionstoreexception")
                || msg.contains("nosuchbeandefinitionexception");
    }

    private static boolean looksLikePersistence(String message) {
        String msg = normalize(message);
        return msg.contains("persistenceexception")
                || msg.contains("dataintegrityviolationexception")
                || msg.contains("hibernate")
                || msg.contains("entitymanagerfactory")
                || msg.contains("sessionfactoryimpl.validatenotclosed")
                || msg.contains("is closed")
                || msg.contains("ddl")
                || msg.contains("constraint")
                || msg.contains("column")
                || msg.contains("relation")
                || looksLikeHibernateNumericScaleMapping(msg);
    }

    private static boolean looksLikeHibernateNumericScaleMapping(String message) {
        String msg = normalize(message);
        return msg.contains("scale has no meaning for floating point numbers")
                || (msg.contains("precision") && msg.contains("scale") && (msg.contains("double") || msg.contains("float")))
                || (msg.contains("floating point") && msg.contains("scale"));
    }

    private static String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private static Throwable findRootCause(Throwable throwable) {
        Throwable current = throwable;
        Throwable last = null;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> target) {
        Throwable current = throwable;
        while (current != null) {
            if (target.isAssignableFrom(current.getClass())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

