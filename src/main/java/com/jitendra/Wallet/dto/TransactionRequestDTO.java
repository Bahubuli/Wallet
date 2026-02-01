package com.jitendra.Wallet.dto;

import java.math.BigDecimal;

import com.jitendra.Wallet.entity.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequestDTO {
    
    private String description;
    
    private Long sourceWalletId;
    
    private Long destinationWalletId;
    
    private BigDecimal amount;
    
    private TransactionType type;
}
