package cz.rychter.trade.resource;

import cz.rychter.trade.dto.TradeReportRequest;
import cz.rychter.trade.dto.TradeReportResponse;
import cz.rychter.trade.model.TradeStatus;
import cz.rychter.trade.model.TradeTransaction;
import cz.rychter.trade.repository.TradeTransactionRepository;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/v1/trades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TradeTransactionResource {

    private final TradeTransactionRepository tradeRepository;

    @Inject
    public TradeTransactionResource(TradeTransactionRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    // Default constructor for CDI proxying
    public TradeTransactionResource() {
        this.tradeRepository = null;
    }

    @POST
    @Transactional
    @RunOnVirtualThread
    public Response reportTrade(@Valid @NotNull TradeReportRequest request) {
        if (tradeRepository.findByTransactionId(request.transactionId()).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                            "error", "Conflict",
                            "message", "Trade transaction with ID '" + request.transactionId() + "' already exists"
                    ))
                    .build();
        }

        BigDecimal grossAmount = request.quantity().multiply(request.price());
        TradeStatus status = request.status() != null ? request.status() : TradeStatus.NEW;

        TradeTransaction entity = new TradeTransaction(
                request.transactionId(),
                request.isin(),
                request.ticker().toUpperCase(),
                request.side(),
                request.quantity(),
                request.price(),
                request.currency().toUpperCase(),
                grossAmount,
                request.tradeTimestamp(),
                request.brokerLei().toUpperCase(),
                request.clientId(),
                request.venueMic().toUpperCase(),
                status,
                Instant.now()
        );

        tradeRepository.persist(entity);

        TradeReportResponse responsePayload = TradeReportResponse.from(entity);
        URI location = URI.create("/api/v1/trades/" + entity.getId());

        return Response.created(location).entity(responsePayload).build();
    }

    @GET
    @Path("/{id}")
    @RunOnVirtualThread
    public Response getTradeById(@PathParam("id") Long id) {
        return tradeRepository.findByIdOptional(id)
                .map(TradeReportResponse::from)
                .map(trade -> Response.ok(trade).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Not Found", "message", "Trade not found with ID " + id))
                        .build());
    }

    @GET
    @Path("/tx/{transactionId}")
    @RunOnVirtualThread
    public Response getTradeByTransactionId(@PathParam("transactionId") String transactionId) {
        return tradeRepository.findByTransactionId(transactionId)
                .map(TradeReportResponse::from)
                .map(trade -> Response.ok(trade).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Not Found", "message", "Trade not found with transaction ID " + transactionId))
                        .build());
    }

    @GET
    @RunOnVirtualThread
    public List<TradeReportResponse> listTrades(
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("page") @DefaultValue("0") int page
    ) {
        return tradeRepository.findAll(Sort.by("tradeTimestamp").descending())
                .page(page, limit)
                .list()
                .stream()
                .map(TradeReportResponse::from)
                .toList();
    }
}
