package ru.serega6531.packmate.model;

import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@GenericGenerator(
        name = "found_pattern_generator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @Parameter(name = "sequence_name", value = "found_pattern_seq"),
                @Parameter(name = "initial_value", value = "1"),
                @Parameter(name = "increment_size", value = "2000"),
                @Parameter(name = "optimizer", value = "hilo")
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@ToString
@Table(indexes = { @Index(name = "found_pattern_packet_id_index", columnList = "packet_id DESC") })
public class FoundPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "found_pattern_generator")
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "packet_id", nullable = false)
    @Setter
    private Packet packet;

    private int patternId;

    private int startPosition;

    private int endPosition;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        FoundPattern that = (FoundPattern) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
