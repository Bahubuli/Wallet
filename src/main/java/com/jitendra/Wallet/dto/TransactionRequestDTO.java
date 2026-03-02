package com.jitendra.Wallet.dto;

import java.math.BigDecimal;

import com.jitendra.Wallet.entity.TransactionType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequestDTO {
    
    private String description;
    
    @NotNull(message = "Source wallet ID is required")
    private Long sourceWalletId;
    
    @NotNull(message = "Destination wallet ID is required")
    private Long destinationWalletId;
    
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;
    
    @NotNull(message = "Transaction type is required")
    private TransactionType type;
}
