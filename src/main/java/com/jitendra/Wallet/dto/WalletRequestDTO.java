package com.jitendra.Wallet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletRequestDTO {

    private Long userId;
    private Boolean isActive = true;
    private BigDecimal balance = BigDecimal.ZERO;
}
