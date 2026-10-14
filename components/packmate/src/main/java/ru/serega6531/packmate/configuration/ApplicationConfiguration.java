package ru.serega6531.packmate.configuration;

import org.modelmapper.Converter;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeMap;
import org.pcap4j.core.PcapNativeException;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.serega6531.packmate.model.Pattern;
import ru.serega6531.packmate.model.Stream;
import ru.serega6531.packmate.model.pojo.StreamDto;
import ru.serega6531.packmate.pcap.FilePcapWorker;
import ru.serega6531.packmate.pcap.LivePcapWorker;
import ru.serega6531.packmate.pcap.NoOpPcapWorker;
import ru.serega6531.packmate.pcap.PcapWorker;
import ru.serega6531.packmate.properties.PackmateProperties;
import ru.serega6531.packmate.service.ServicesService;
import ru.serega6531.packmate.service.StreamService;

import java.net.UnknownHostException;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan("ru.serega6531.packmate.properties")
public class ApplicationConfiguration {

    @Bean(destroyMethod = "shutdown")
    public PcapWorker pcapWorker(ServicesService servicesService,
                                 StreamService streamService,
                                 PackmateProperties properties
    ) throws PcapNativeException, UnknownHostException {
        return switch (properties.captureMode()) {
            case LIVE -> new LivePcapWorker(servicesService, streamService, properties.localIp(), properties.interfaceName());
            case FILE ->
                    new FilePcapWorker(servicesService, streamService, properties.localIp(), properties.pcapFile());
            case VIEW -> new NoOpPcapWorker();
        };
    }

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();

        addStreamMapper(modelMapper);

        return modelMapper;
    }

    private void addStreamMapper(ModelMapper modelMapper) {
        TypeMap<Stream, StreamDto> streamMapper = modelMapper.createTypeMap(Stream.class, StreamDto.class);

        Converter<Set<Pattern>, Set<Integer>> patternSetToIdSet = ctx -> ctx.getSource()
                .stream()
                .map(Pattern::getId)
                .collect(Collectors.toSet());

        streamMapper.addMappings(mapping ->
                mapping.using(patternSetToIdSet)
                        .map(Stream::getFoundPatterns, StreamDto::setFoundPatternsIds)
        );
    }

}
