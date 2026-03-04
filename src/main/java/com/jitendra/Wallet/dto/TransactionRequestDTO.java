package com.jitendra.Wallet.dto;

import java.math.BigDecimal;

import com.jitendra.Wallet.entity.TransactionType;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequestDTO {

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @NotNull(message = "Source wallet ID must not be null")
    @Positive(message = "Source wallet ID must be a positive number")
    private Long sourceWalletId;

    @NotNull(message = "Destination wallet ID must not be null")
    @Positive(message = "Destination wallet ID must be a positive number")
    private Long destinationWalletId;

    @NotNull(message = "Amount must not be null")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotNull(message = "Transaction type must not be null")
    private TransactionType type;
}
