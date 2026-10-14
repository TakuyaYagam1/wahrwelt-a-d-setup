package ru.serega6531.packmate.exception.analyzer;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import ru.serega6531.packmate.exception.PcapFileNotFoundException;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class PcapFileNotFoundFailureAnalyzer extends AbstractFailureAnalyzer<PcapFileNotFoundException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PcapFileNotFoundException cause) {
        String description = "Файл " + cause.getFile().getAbsolutePath() + " не найден.";
        String existingFilesMessage;

        File[] existingFiles = cause.getDirectory().listFiles();

        if (existingFiles == null) {
            return new FailureAnalysis(
                    description,
                    "Поместите файл PCAP в каталог ./pcaps. Сейчас этого каталога нет.",
                    cause
            );
        }

        if (existingFiles.length == 0) {
            existingFilesMessage = "Каталог ./pcaps пуст.";
        } else {
            List<String> existingFilesNames = Arrays.stream(existingFiles).map(File::getName).toList();
            existingFilesMessage = "В каталоге " + cause.getDirectory().getAbsolutePath() + " найдены файлы: " + existingFilesNames;
        }

        return new FailureAnalysis(
                description,
                "Проверьте имя файла и поместите его в каталог ./pcaps.\n" +
                        existingFilesMessage,
                cause
        );
    }
}
