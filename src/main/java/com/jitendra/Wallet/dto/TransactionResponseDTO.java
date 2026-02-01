package com.jitendra.Wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.entity.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponseDTO {
    
    private Long id;
    
    private String description;
    
    private Long sourceWalletId;
    
    private Long destinationWalletId;
    
    private BigDecimal amount;
    
    private TransactionStatus status;
    
    private TransactionType type;
    
    private Long sagaInstanceId;
    
    private Instant createdDate;
    
    private Instant updatedDate;
}
