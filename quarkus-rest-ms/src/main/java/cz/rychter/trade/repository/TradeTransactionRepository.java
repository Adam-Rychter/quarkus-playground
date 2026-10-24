package cz.rychter.trade.repository;

import cz.rychter.trade.model.TradeTransaction;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class TradeTransactionRepository implements PanacheRepository<TradeTransaction> {

    public Optional<TradeTransaction> findByTransactionId(String transactionId) {
        return find("transactionId", transactionId).firstResultOptional();
    }
}
