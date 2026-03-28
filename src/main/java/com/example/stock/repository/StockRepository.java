package com.example.stock.repository;

import com.example.stock.domain.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class StockRepository {

    private final StockJpaRepository stockJpaRepository;

    public Optional<Stock> findById(Long id) {
        return stockJpaRepository.findById(id);
    }

    public Optional<Stock> findByIdForUpdate(Long id) {
        return stockJpaRepository.findByIdForUpdate(id);
    }

    public Stock save(Stock stock) {
        return stockJpaRepository.save(stock);
    }

    public Stock saveAndFlush(Stock stock) {
        return stockJpaRepository.saveAndFlush(stock);
    }

    public void deleteAll() {
        stockJpaRepository.deleteAll();
    }
}
