package cz.rychter;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;

@Path("/time")
public class TimeResource {

    @Inject
    TimeRepository timeRepository;

    @GET
    @Transactional
    @RunOnVirtualThread
    @Produces(MediaType.APPLICATION_JSON)
    public TimeResponse getCurrentTime() {
        Instant now = Instant.now();

        TimeEntry entry = new TimeEntry(now);
        timeRepository.persist(entry);

        return new TimeResponse(entry.getId(), entry.getTimestamp());
    }
}
