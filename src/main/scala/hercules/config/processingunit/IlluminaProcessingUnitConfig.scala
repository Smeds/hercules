package hercules.config.processingunit

import java.io.File

/**
 * Provides the configuration files for a IlluminaProcessingUnit
 */
case class IlluminaProcessingUnitConfig(
    sampleSheet: File,
    QCConfig: File,
    programConfig: Option[File]) extends ProcessingUnitConfig {

}