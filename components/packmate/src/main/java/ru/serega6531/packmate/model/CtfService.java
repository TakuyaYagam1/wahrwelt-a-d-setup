package ru.serega6531.packmate.model;

import lombok.*;
import org.hibernate.Hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@Table(name = "service")
public class CtfService {

    @Id
    private Integer port;

    @Column(nullable = false)
    private String name;

    private boolean decryptTls;

    private boolean http;

    private boolean urldecodeHttpRequests;

    private boolean mergeAdjacentPackets;

    private boolean parseWebSockets;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        CtfService that = (CtfService) o;
        return port != null && Objects.equals(port, that.port);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}