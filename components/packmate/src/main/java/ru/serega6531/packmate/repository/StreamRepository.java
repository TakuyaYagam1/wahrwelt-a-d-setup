package ru.serega6531.packmate.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ru.serega6531.packmate.model.Packet;
import ru.serega6531.packmate.model.Stream;

import java.util.List;

public interface StreamRepository extends JpaRepository<Stream, Long>, JpaSpecificationExecutor<Stream> {

    @Query("UPDATE Stream SET favorite = :favorite WHERE id = :id")
    @Modifying
    void setFavorite(long id, boolean favorite);

    long deleteByEndTimestampBeforeAndFavoriteIsFalse(long threshold);

    @Query("SELECT p FROM Packet p " +
            "LEFT JOIN FETCH p.matches " +
            "WHERE p.stream.id = :streamId " +
            "AND (:startingFrom IS NULL OR p.id > :startingFrom) " +
            "ORDER BY p.id"
    )
    List<Packet> getPackets(long streamId, Long startingFrom, Pageable pageable);

}
