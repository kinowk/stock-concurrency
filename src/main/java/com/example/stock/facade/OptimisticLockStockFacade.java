package com.example.stock.facade;

import com.example.stock.service.OptimisticLockStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OptimisticLockStockFacade {

    private final OptimisticLockStockService stockService;

    @Retryable(
            include = ObjectOptimisticLockingFailureException.class,
            maxAttempts = Integer.MAX_VALUE,
            backoff = @Backoff(delay = 50)
    )
    public void decrease(Long id, Long quantity) {
        stockService.decrease(id, quantity);
    }
}
