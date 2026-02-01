package com.jitendra.Wallet.services.saga;


public interface SagaStepInterface {
    
    /**
     * Execute this step's action.
     * 
     * @param context - Shared context containing saga data
     * @throws Exception - If execution fails
     */
    boolean execute(SagaContext context) throws Exception;
    
    /**
     * Compensate/rollback this step's changes.
     * Called when a subsequent step fails and saga needs to roll back.
     * Must be idempotent - safe to call multiple times.
     * 
     * @param context - Shared context containing saga data
     * @throws Exception - If compensation fails (may require manual intervention)
     */
    boolean compensate(SagaContext context) throws Exception;
    
    /**
     * Get the unique name of this step
     * @return step name (e.g., "CREATE_USER", "ALLOCATE_WALLET")
     */
    String getStepName();
    
    /**
     * Get the execution order/sequence of this step
     * Lower numbers execute first (e.g., 1, 2, 3...)
     * @return step order
     */
    Integer getStepOrder();
    
    /**
     * Get the compensation action name
     * @return compensation action identifier (default: "compensate_" + stepName)
     */
    default String getCompensationAction() {
        return "compensate_" + getStepName();
    }
    
    /**
     * Get maximum retry attempts for this step
     * @return max retries (default: 3)
     */
    default Integer getMaxRetries() {
        return 3;
    }
    
    /**
     * Pre-execution validation - override to add custom validation logic.
     * Called before execute() to validate if step can proceed.
     * 
     * @param context - Shared context containing saga data
     * @return true if validation passes, false otherwise
     */
    default boolean validate(SagaContext context) {
        return true;
    }
    
    /**
     * Post-execution hook - override to add custom post-processing logic.
     * Called after execute() completes successfully.
     * 
     * @param context - Shared context containing saga data
     */
    default void onSuccess(SagaContext context) {
        // Optional hook for implementations
    }
    
    /**
     * Failure hook - override to add custom error handling logic.
     * Called when execute() throws an exception.
     * 
     * @param context - Shared context containing saga data
     * @param error - The exception that caused the failure
     */
    default void onFailure(SagaContext context, Exception error) {
        // Optional hook for implementations
    }
}
