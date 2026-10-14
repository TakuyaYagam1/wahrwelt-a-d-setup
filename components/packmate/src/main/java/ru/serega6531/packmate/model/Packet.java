package ru.serega6531.packmate.model;

import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.Set;

@Getter
@Setter
@RequiredArgsConstructor
@Entity
@GenericGenerator(
        name = "packet_generator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @Parameter(name = "sequence_name", value = "packet_seq"),
                @Parameter(name = "initial_value", value = "1"),
                @Parameter(name = "increment_size", value = "20000"),
                @Parameter(name = "optimizer", value = "hilo")
        }
)
@AllArgsConstructor
@Builder(toBuilder = true)
@Table(indexes = { @Index(name = "stream_id_index", columnList = "stream_id") })
public class Packet {

    @Id
    @GeneratedValue(generator = "packet_generator")
    private Long id;

    @Transient
    private Long tempId;

    @Transient
    private int ttl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stream_id", nullable = false)
    private Stream stream;

    @OneToMany(mappedBy = "packet", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<FoundPattern> matches;

    private long timestamp;

    private boolean incoming; // true, если пакет идет от клиента к серверу.

    private boolean httpProcessed = false;

    private boolean webSocketParsed = false;

    private boolean tlsDecrypted = false;

    private boolean hasHttpBody = false;

    @Column(nullable = false)
    private byte[] content;

    @Transient
    public String getContentString() {
        return new String(content);
    }

    public String toString() {
        return "Packet(id=" + id + ", content=" + getContentString() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Packet packet = (Packet) o;
        return id != null && Objects.equals(id, packet.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
