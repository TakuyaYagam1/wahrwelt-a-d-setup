package ru.serega6531.packmate.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import ru.serega6531.packmate.model.enums.Protocol;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@GenericGenerator(
        name = "stream_generator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @Parameter(name = "sequence_name", value = "stream_seq"),
                @Parameter(name = "initial_value", value = "1"),
                @Parameter(name = "increment_size", value = "1000"),
                @Parameter(name = "optimizer", value = "hilo")
        }
)
@Table(indexes = {@Index(name = "stream_id_desc_index", columnList = "id DESC")})
public class Stream {

    @Id
    @GeneratedValue(generator = "stream_generator")
    private Long id;

    @Column(name = "service_id", nullable = false)
    private int service;

    @Enumerated
    @Column(nullable = false)
    private Protocol protocol;

    @OneToMany(mappedBy = "stream", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @ToString.Exclude
    private List<Packet> packets;

    private long startTimestamp;

    private long endTimestamp;

    @ManyToMany
    @JoinTable(
            name = "stream_found_patterns",
            joinColumns = @JoinColumn(name = "stream_id"),
            inverseJoinColumns = @JoinColumn(name = "pattern_id")
    )
    @ToString.Exclude
    private Set<Pattern> foundPatterns = new HashSet<>();

    private boolean favorite;

    @Column(nullable = false, columnDefinition = "smallint")
    private int ttl;

    @Column(columnDefinition = "char(3)")
    private String userAgentHash;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @Column(name = "packets_count", nullable = false)
    private Integer packetsCount;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Stream stream = (Stream) o;
        return id != null && Objects.equals(id, stream.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
