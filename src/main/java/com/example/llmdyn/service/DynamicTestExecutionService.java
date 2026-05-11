package com.example.llmdyn.service;

import com.example.llmdyn.runtime.BeanRegistrar;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DynamicTestExecutionService {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;

    public DynamicTestExecutionService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Allow YAML-parsed numbers (Integer, Double) to be coerced into
        // entity field types (Long, Float, Short, BigDecimal) automatically.
        this.objectMapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, false);
        this.objectMapper.configure(DeserializationFeature.USE_LONG_FOR_INTS, false);
        // Safety net: after normalizeIsPrefixKeys handles isXxx->xxx renaming,
        // any remaining unrecognized keys are silently ignored rather than throwing.
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // -------------------------------------------------------------------------
    // PUBLIC ENTRY POINT
    // -------------------------------------------------------------------------

    public TestResult executeTest(String beanName, String methodName, Object[] args, Object expectedResult) throws Exception {
        System.out.println("Looking for bean: " + beanName);
        System.out.println("Executing: " + methodName + " with args: " + Arrays.toString(args) +
                " (types: " + Arrays.stream(args != null ? args : new Object[0])
                .map(a -> a != null ? a.getClass().getSimpleName() : "null")
                .collect(Collectors.joining(", ")) + ")");

        Object actualResult;
        String simpleBeanName = beanName;

        if (beanName.contains(".")) {
            String className = beanName.substring(beanName.lastIndexOf('.') + 1);
            simpleBeanName = Character.toLowerCase(className.charAt(0)) + className.substring(1);
        }

        if (applicationContext.containsBean(simpleBeanName)) {
            Object bean = applicationContext.getBean(simpleBeanName);
            System.out.println("✅ Found bean: " + bean.getClass().getName());

            Class<?> clazz = bean.getClass();
            Method method = findMethod(clazz, methodName, args);
            Object[] convertedArgs = convertArguments(method.getParameterTypes(), args);
            actualResult = method.invoke(bean, convertedArgs);
        } else {
            System.out.println("❌ Bean not found, searching for non-bean class...");
            Class<?> clazz = null;

            if (beanName.contains(".")) {
                System.out.println("Attempting to load fully qualified class: " + beanName);
                clazz = Class.forName(beanName);
            } else {
                BeanRegistrar beanRegistrar = applicationContext.getBean(BeanRegistrar.class);
                String className = Character.toUpperCase(beanName.charAt(0)) + beanName.substring(1);

                List<Class<?>> registeredNonBeans = beanRegistrar.getRegisteredNonBeans();
                System.out.println("📋 Registered non-beans (" + registeredNonBeans.size() + "):");
                for (Class<?> c : registeredNonBeans) {
                    System.out.println("  - " + c.getSimpleName() + " (" + c.getName() + ")");
                }

                for (Class<?> nonBeanClass : registeredNonBeans) {
                    System.out.println("Comparing: " + nonBeanClass.getSimpleName() + " with " + className);
                    if (nonBeanClass.getSimpleName().equals(className)) {
                        clazz = nonBeanClass;
                        System.out.println("✅ Match found!");
                        break;
                    }
                }

                if (clazz == null) {
                    throw new ClassNotFoundException("Non-bean class not found: " + className);
                }
            }

            System.out.println("✅ Not a bean, calling static method on: " + clazz.getName());

            Method method = findMethod(clazz, methodName, args);
            Object[] convertedArgs = convertArguments(method.getParameterTypes(), args);
            actualResult = method.invoke(null, convertedArgs);
        }

        if (actualResult instanceof Optional) {
            actualResult = ((Optional<?>) actualResult).orElse(null);
        }

        boolean success = compareResults(actualResult, expectedResult);
        return new TestResult(actualResult, expectedResult, success);
    }

    // -------------------------------------------------------------------------
    // COMPARISON
    // -------------------------------------------------------------------------

    private boolean compareResults(Object actual, Object expected) {
        if (expected == null) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }

        if (actual instanceof Optional) {
            actual = ((Optional<?>) actual).orElse(null);
        }
        if (expected instanceof Optional) {
            expected = ((Optional<?>) expected).orElse(null);
        }

        if (isPrimitive(actual) && isPrimitive(expected)) {
            return comparePrimitives(actual, expected);
        }

        boolean actualIsArray = isArray(actual);
        boolean expectedIsArray = isArray(expected);

        if (actualIsArray && expected instanceof List) {
            return compareLists((List<?>) expected, arrayToList(actual));
        }
        if (expectedIsArray && actual instanceof List) {
            return compareLists(arrayToList(expected), (List<?>) actual);
        }
        if (actualIsArray && expectedIsArray) {
            return compareLists(arrayToList(expected), arrayToList(actual));
        }

        if (expected instanceof List && actual instanceof List) {
            return compareLists((List<?>) expected, (List<?>) actual);
        }

        // Fallback: convert both sides to maps and compare field by field.
        // Jackson serializes the actual entity using its getter names, which
        // produces camelCase keys matching the YAML expected map keys.
        Map<String, Object> actualMap = objectMapper.convertValue(actual, Map.class);
        Map<String, Object> expectedMap = objectMapper.convertValue(expected, Map.class);
        return compareMapFields(expectedMap, actualMap);
    }

    private boolean isPrimitive(Object obj) {
        return obj instanceof String ||
                obj instanceof Number ||
                obj instanceof Boolean ||
                obj instanceof Character;
    }

    private boolean isArray(Object obj) {
        return obj != null && obj.getClass().isArray();
    }

    private List<?> arrayToList(Object array) {
        if (array instanceof int[]) {
            return Arrays.stream((int[]) array).boxed().collect(Collectors.toList());
        } else if (array instanceof long[]) {
            long[] a = (long[]) array;
            List<Long> list = new ArrayList<>(a.length);
            for (long v : a) list.add(v);
            return list;
        } else if (array instanceof double[]) {
            double[] a = (double[]) array;
            List<Double> list = new ArrayList<>(a.length);
            for (double v : a) list.add(v);
            return list;
        } else if (array instanceof float[]) {
            float[] a = (float[]) array;
            List<Float> list = new ArrayList<>(a.length);
            for (float v : a) list.add(v);
            return list;
        } else if (array instanceof short[]) {
            short[] a = (short[]) array;
            List<Short> list = new ArrayList<>(a.length);
            for (short v : a) list.add(v);
            return list;
        } else if (array instanceof byte[]) {
            byte[] a = (byte[]) array;
            List<Byte> list = new ArrayList<>(a.length);
            for (byte v : a) list.add(v);
            return list;
        } else if (array instanceof char[]) {
            char[] a = (char[]) array;
            List<Character> list = new ArrayList<>(a.length);
            for (char v : a) list.add(v);
            return list;
        } else if (array instanceof boolean[]) {
            boolean[] a = (boolean[]) array;
            List<Boolean> list = new ArrayList<>(a.length);
            for (boolean v : a) list.add(v);
            return list;
        } else {
            return Arrays.asList((Object[]) array);
        }
    }

    private boolean comparePrimitives(Object actual, Object expected) {
        if (actual instanceof Number && expected instanceof Number) {
            return compareNumbers((Number) expected, (Number) actual);
        }
        if (actual instanceof String || expected instanceof String) {
            return String.valueOf(actual).equals(String.valueOf(expected));
        }
        return actual.equals(expected);
    }

    private boolean compareLists(List<?> expected, List<?> actual) {
        if (expected.size() != actual.size()) {
            System.out.println("List size mismatch: expected=" + expected.size() + ", actual=" + actual.size());
            return false;
        }

        for (int i = 0; i < expected.size(); i++) {
            Object expectedItem = expected.get(i);
            Object actualItem = actual.get(i);

            if (expectedItem instanceof Map) {
                Map<String, Object> expectedMap = (Map<String, Object>) expectedItem;
                Map<String, Object> actualMap = objectMapper.convertValue(actualItem, Map.class);
                if (!compareMapFields(expectedMap, actualMap)) {
                    return false;
                }
            } else if (!Objects.equals(expectedItem, actualItem)) {
                System.out.println("List item " + i + " direct comparison failed");
                return false;
            }
        }
        return true;
    }

    private boolean compareMapFields(Map<String, Object> expected, Map<String, Object> actual) {
        System.out.println("Comparing map fields:");
        System.out.println("  Expected keys: " + expected.keySet());
        System.out.println("  Actual keys:   " + actual.keySet());

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();

            if (!actual.containsKey(key)) {
                System.out.println("  ❌ Missing key in actual: " + key);
                return false;
            }

            Object actualValue = actual.get(key);
            Object expectedValue = entry.getValue();

            System.out.println("  Comparing key '" + key + "':");
            System.out.println("    Expected: " + expectedValue +
                    " (type: " + (expectedValue != null ? expectedValue.getClass().getSimpleName() : "null") + ")");
            System.out.println("    Actual:   " + actualValue +
                    " (type: " + (actualValue != null ? actualValue.getClass().getSimpleName() : "null") + ")");

            boolean match;
            if (isNumeric(actualValue) && isNumeric(expectedValue)) {
                match = compareNumbers((Number) expectedValue, (Number) actualValue);
                System.out.println("    Number comparison: " + (match ? "✓ PASS" : "❌ FAIL"));
            } else if (expectedValue instanceof Map && actualValue instanceof Map) {
                match = compareMapFields((Map<String, Object>) expectedValue, (Map<String, Object>) actualValue);
                if (!match) System.out.println("    ❌ Nested map comparison failed");
            } else {
                match = Objects.equals(String.valueOf(actualValue), String.valueOf(expectedValue));
                System.out.println("    Direct comparison: " + (match ? "✓ PASS" : "❌ FAIL"));
            }

            if (!match) return false;
        }

        System.out.println("  ✓ All fields matched");
        return true;
    }

    private boolean isNumeric(Object value) {
        return value instanceof Number;
    }

    private boolean compareNumbers(Number expected, Number actual) {
        if (expected instanceof BigDecimal || actual instanceof BigDecimal) {
            BigDecimal exp = expected instanceof BigDecimal
                    ? (BigDecimal) expected
                    : new BigDecimal(expected.toString());
            BigDecimal act = actual instanceof BigDecimal
                    ? (BigDecimal) actual
                    : new BigDecimal(actual.toString());
            return exp.compareTo(act) == 0;
        }
        return Math.abs(expected.doubleValue() - actual.doubleValue()) < 0.0001;
    }

    // -------------------------------------------------------------------------
    // METHOD RESOLUTION
    // -------------------------------------------------------------------------

    private Method findMethod(Class<?> clazz, String methodName, Object[] args) throws NoSuchMethodException {
        int paramCount = args == null ? 0 : args.length;

        Method[] candidates = Arrays.stream(clazz.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .toArray(Method[]::new);

        if (candidates.length == 0) {
            throw new NoSuchMethodException("No method found with name: " + methodName);
        }

        System.out.println("Found " + candidates.length + " method(s) named " + methodName + ":");
        for (Method m : candidates) {
            System.out.println("  - " + m.getName() + "(" +
                    Arrays.stream(m.getParameterTypes())
                            .map(Class::getSimpleName)
                            .collect(Collectors.joining(", ")) + ")");
        }

        for (Method method : candidates) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != paramCount) continue;
            if (paramCount == 0) return method;
            if (areParametersCompatible(paramTypes, args)) {
                System.out.println("Selected method: " + method);
                return method;
            }
        }

        throw new NoSuchMethodException("No compatible method found: " + methodName +
                " with " + paramCount + " parameter(s). Provided args: " + Arrays.toString(args));
    }

    private boolean areParametersCompatible(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) return false;

        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) {
                if (paramTypes[i].isPrimitive()) return false;
                continue;
            }

            Class<?> argType = args[i].getClass();

            // Map arg → entity param: always convertible via ObjectMapper
            if ((argType == LinkedHashMap.class || argType == HashMap.class)
                    && !paramTypes[i].isPrimitive()
                    && !isWrapperType(paramTypes[i])) {
                continue;
            }

            if (paramTypes[i].isArray() && args[i] instanceof List) continue;
            if (isNumericConversionPossible(paramTypes[i], argType)) continue;
            if (!isCompatible(paramTypes[i], argType)) return false;
        }
        return true;
    }

    private boolean isNumericConversionPossible(Class<?> targetType, Class<?> sourceType) {
        boolean sourceIsNumeric = Number.class.isAssignableFrom(sourceType) || isNumericPrimitive(sourceType);
        boolean targetIsNumeric = Number.class.isAssignableFrom(targetType)
                || isNumericPrimitive(targetType)
                || targetType == BigDecimal.class;
        return sourceIsNumeric && targetIsNumeric;
    }

    private boolean isNumericPrimitive(Class<?> type) {
        return type == int.class || type == long.class || type == double.class
                || type == float.class || type == short.class || type == byte.class;
    }

    private boolean isWrapperType(Class<?> clazz) {
        return clazz == Integer.class || clazz == Long.class || clazz == Double.class
                || clazz == Float.class || clazz == Boolean.class || clazz == Byte.class
                || clazz == Short.class || clazz == Character.class || clazz == String.class;
    }

    private boolean isCompatible(Class<?> paramType, Class<?> argType) {
        if (paramType.equals(argType)) return true;
        if (paramType.isPrimitive()) return getPrimitiveWrapper(paramType).equals(argType);
        if (argType.isPrimitive()) return getPrimitiveWrapper(argType).equals(paramType);
        return paramType.isAssignableFrom(argType);
    }

    private Class<?> getPrimitiveWrapper(Class<?> primitiveType) {
        if (primitiveType == int.class)     return Integer.class;
        if (primitiveType == long.class)    return Long.class;
        if (primitiveType == double.class)  return Double.class;
        if (primitiveType == float.class)   return Float.class;
        if (primitiveType == boolean.class) return Boolean.class;
        if (primitiveType == byte.class)    return Byte.class;
        if (primitiveType == short.class)   return Short.class;
        if (primitiveType == char.class)    return Character.class;
        return primitiveType;
    }

    // -------------------------------------------------------------------------
    // ARGUMENT CONVERSION
    // -------------------------------------------------------------------------

    private Object[] convertArguments(Class<?>[] paramTypes, Object[] args) {
        if (args == null || args.length == 0) return args;

        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (paramTypes[i].isArray() && args[i] instanceof List) {
                converted[i] = convertListToArray((List<?>) args[i], paramTypes[i].getComponentType());
            } else if (args[i] instanceof Map && !paramTypes[i].isAssignableFrom(Map.class)) {
                // Delegate entirely to Jackson — handles all field types, boolean naming,
                // LocalDateTime, Float, Short, BigDecimal, @JsonProperty, etc.
                converted[i] = convertMapToObject((Map<?, ?>) args[i], paramTypes[i]);
            } else {
                converted[i] = convertScalarValue(args[i], paramTypes[i]);
            }
        }
        return converted;
    }

    private Object convertListToArray(List<?> list, Class<?> componentType) {
        if (componentType == int.class)    return list.stream().mapToInt(o -> ((Number) o).intValue()).toArray();
        if (componentType == Integer.class) return list.stream().map(o -> ((Number) o).intValue()).toArray(Integer[]::new);
        if (componentType == String.class) return list.toArray(new String[0]);
        return list.toArray();
    }

    /**
     * Converts a Map (from YAML test args) to a typed entity/DTO object using Jackson.
     *
     * Before passing the map to Jackson we normalize any "isXxx" boolean keys to "xxx",
     * because the YAML uses the raw field name (e.g. "isActive") but Jackson derives the
     * property name from the getter (isActive() -> "active"). Without normalization Jackson
     * throws UnrecognizedPropertyException.
     *
     * Requirements on the target class (enforced via prompt rules):
     *   - Must have a public no-args constructor (Jackson BeanDeserializer requires it)
     *   - Fields must have standard getter/setter pairs
     */
    private Object convertMapToObject(Map<?, ?> map, Class<?> targetType) {
        try {
            Map<String, Object> normalized = normalizeIsPrefixKeys(map);
            return objectMapper.convertValue(normalized, targetType);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    "Failed to convert map to " + targetType.getSimpleName() + ". " +
                            "Map keys: " + map.keySet() + ". " +
                            "Ensure the entity has a public no-args constructor and matching getters/setters. " +
                            "Cause: " + e.getMessage(), e);
        }
    }

    /**
     * Normalizes map keys that use an "isXxx" boolean prefix to "xxx" (camelCase).
     *
     * YAML test args use field names as-is: "isActive", "isRead", "isEdited".
     * Jackson derives property names from getters: isActive() -> "active".
     * This strips the "is" prefix when followed by an uppercase letter.
     *
     * Examples:
     *   "isActive" -> "active"
     *   "isRead"   -> "read"
     *   "island"   -> unchanged ('l' is not uppercase)
     */
    private Map<String, Object> normalizeIsPrefixKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = entry.getKey().toString();
            if (key.length() > 2 && key.startsWith("is") && Character.isUpperCase(key.charAt(2))) {
                String normalized = Character.toLowerCase(key.charAt(2)) + key.substring(3);
                System.out.println("  [key normalize] \"" + key + "\" -> \"" + normalized + "\"");
                result.put(normalized, entry.getValue());
            } else {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    /**
     * Converts scalar argument values (IDs, primitive numerics, date strings)
     * for direct method parameters — not for map-to-entity conversion.
     * Map args go through convertMapToObject() instead.
     */
    private Object convertScalarValue(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) return value;

        // Primitive boolean: isInstance() returns false for primitive type even with Boolean value
        if (targetType == boolean.class && value instanceof Boolean) return value;

        // Date/time from String
        if (targetType == LocalDate.class && value instanceof String) {
            return LocalDate.parse((String) value);
        }
        if (targetType == LocalDateTime.class && value instanceof String) {
            return LocalDateTime.parse((String) value);
        }
        if (targetType == LocalTime.class && value instanceof String) {
            return LocalTime.parse((String) value);
        }

        // Numeric coercions — YAML always deserializes numbers as Integer or Double
        if (value instanceof Number) {
            if (targetType == BigDecimal.class)                   return new BigDecimal(value.toString());
            if (targetType == int.class || targetType == Integer.class) return ((Number) value).intValue();
            if (targetType == long.class || targetType == Long.class)   return ((Number) value).longValue();
            if (targetType == double.class || targetType == Double.class) return ((Number) value).doubleValue();
            if (targetType == float.class || targetType == Float.class)  return ((Number) value).floatValue();
            if (targetType == short.class || targetType == Short.class)  return ((Number) value).shortValue();
        }

        if (targetType == BigDecimal.class && value instanceof String) {
            return new BigDecimal((String) value);
        }

        return value;
    }

    // -------------------------------------------------------------------------
    // TEST RESULT
    // -------------------------------------------------------------------------

    public static class TestResult {
        private final Object actualResult;
        private final Object expectedResult;
        private final boolean success;

        public TestResult(Object actualResult, Object expectedResult, boolean success) {
            this.actualResult = actualResult;
            this.expectedResult = expectedResult;
            this.success = success;
        }

        public Object getActualResult()  { return actualResult; }
        public Object getExpectedResult() { return expectedResult; }
        public boolean isSuccess()        { return success; }
    }
}