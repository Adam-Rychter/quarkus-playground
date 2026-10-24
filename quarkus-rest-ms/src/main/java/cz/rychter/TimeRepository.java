package cz.rychter;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TimeRepository implements PanacheRepository<TimeEntry> {
}
