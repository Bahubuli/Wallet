package com.jitendra.Wallet.services.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Context object that carries data throughout the saga execution.
 * Shared across all saga steps and contains input parameters, intermediate results, and metadata.
 * 
 * This context is serialized to JSON and stored in the saga_instance table for persistence.
 * 
 * Usage examples:
 * - Simple: new SagaContext()
 * - With builder: SagaContext.builder().sagaType("USER_REGISTRATION").sagaInstanceId(123L).build()
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaContext {
    
    // Unique identifier for the saga instance
    private Long sagaInstanceId;
    
    // Type/name of the saga workflow (e.g., "USER_REGISTRATION", "PAYMENT_TRANSFER")
    private String sagaType;
    
    // Storage for saga data - key-value pairs holding input parameters and step results
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();
    
    // Metadata about the saga execution (timestamps, user info, correlation IDs, etc.)
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    // Flag indicating if saga is in compensation mode (rolling back)
    @Builder.Default
    private boolean compensating = false;
    
    // Current retry attempt number
    @Builder.Default
    private Integer retryCount = 0;
    
    // Jackson ObjectMapper for JSON serialization
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // ==================== Data Access Methods ====================
    
    /**
     * Put a value into the saga context
     * @param key - Key to store the value under
     * @param value - Value to store
     */
    public void put(String key, Object value) {
        this.data.put(key, value);
    }
    
    /**
     * Get a value from the saga context
     * @param key - Key to retrieve
     * @return value or null if not found
     */
    public Object get(String key) {
        return this.data.get(key);
    }
    
    /**
     * Get a value with type casting
     * @param key - Key to retrieve
     * @param type - Expected type class
     * @return typed value or null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = this.data.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
    
    /**
     * Get a value as Optional
     * @param key - Key to retrieve
     * @return Optional containing value or empty
     */
    public Optional<Object> getOptional(String key) {
        return Optional.ofNullable(this.data.get(key));
    }
    
    /**
     * Check if context contains a key
     * @param key - Key to check
     * @return true if key exists
     */
    public boolean containsKey(String key) {
        return this.data.containsKey(key);
    }
    
    /**
     * Remove a value from context
     * @param key - Key to remove
     * @return removed value or null
     */
    public Object remove(String key) {
        return this.data.remove(key);
    }
    
    // ==================== Metadata Methods ====================
    
    /**
     * Put metadata value
     * @param key - Metadata key
     * @param value - Metadata value
     */
    public void putMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
    
    /**
     * Get metadata value
     * @param key - Metadata key
     * @return metadata value or null
     */
    public Object getMetadata(String key) {
        return this.metadata.get(key);
    }
    
    /**
     * Get metadata with type casting
     * @param key - Metadata key
     * @param type - Expected type class
     * @return typed metadata value or null
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = this.metadata.get(key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
    
    // ==================== Serialization Methods ====================
    
    /**
     * Serialize context to JSON string for database storage
     * @return JSON string representation
     * @throws JsonProcessingException if serialization fails
     */
    public String toJson() throws JsonProcessingException {
        return objectMapper.writeValueAsString(this);
    }
    
    /**
     * Deserialize context from JSON string
     * @param json - JSON string from database
     * @return SagaContext instance
     * @throws JsonProcessingException if deserialization fails
     */
    public static SagaContext fromJson(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, SagaContext.class);
    }
    
    // ==================== Utility Methods ====================
    
    /**
     * Increment retry count
     */
    public void incrementRetryCount() {
        this.retryCount++;
    }
    
    /**
     * Reset retry count to 0
     */
    public void resetRetryCount() {
        this.retryCount = 0;
    }
    
    /**
     * Mark saga as compensating (rollback mode)
     */
    public void startCompensation() {
        this.compensating = true;
    }
    
    /**
     * Check if saga is currently compensating
     * @return true if in compensation mode
     */
    public boolean isCompensating() {
        return this.compensating;
    }
    
    /**
     * Clear all data in context (use with caution)
     */
    public void clear() {
        this.data.clear();
    }
    
    /**
     * Get size of data map
     * @return number of entries in context
     */
    public int size() {
        return this.data.size();
    }
    
    /**
     * Create a shallow copy of this context
     * @return new SagaContext with copied data
     */
    public SagaContext copy() {
        return SagaContext.builder()
                .sagaInstanceId(this.sagaInstanceId)
                .sagaType(this.sagaType)
                .data(new HashMap<>(this.data))
                .metadata(new HashMap<>(this.metadata))
                .compensating(this.compensating)
                .retryCount(this.retryCount)
                .build();
    }
    
    @Override
    public String toString() {
        return "SagaContext{" +
                "sagaInstanceId=" + sagaInstanceId +
                ", sagaType='" + sagaType + '\'' +
                ", dataSize=" + data.size() +
                ", metadataSize=" + metadata.size() +
                ", compensating=" + compensating +
                ", retryCount=" + retryCount +
                '}';
    }
}
