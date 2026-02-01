package com.jitendra.Wallet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponseDTO {

    private Long id;
    private Long userId;
    private Boolean isActive;
    private BigDecimal balance;
}
