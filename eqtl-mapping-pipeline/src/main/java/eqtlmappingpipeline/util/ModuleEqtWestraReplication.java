/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eqtlmappingpipeline.util;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.molgenis.genotype.Allele;
import org.molgenis.genotype.Alleles;
import org.molgenis.genotype.GenotypeDataException;
import org.molgenis.genotype.GenotypeInfo;
import org.molgenis.genotype.RandomAccessGenotypeData;
import org.molgenis.genotype.RandomAccessGenotypeDataReaderFormats;
import org.molgenis.genotype.multipart.IncompatibleMultiPartGenotypeDataException;
import org.molgenis.genotype.tabix.TabixFileNotFoundException;
import org.molgenis.genotype.util.Ld;
import org.molgenis.genotype.util.LdCalculatorException;
import org.molgenis.genotype.util.Utils;
import org.molgenis.genotype.variant.GeneticVariant;
import umcg.genetica.collections.ChrPosTreeMap;

/**
 *
 * @author patri
 */
public class ModuleEqtWestraReplication {

	private static final String HEADER
			= "  /---------------------------------------\\\n"
			+ "  |                                       |\n"
			+ "  |                                       |\n"
			+ "  |             Patrick Deelen            |\n"
			+ "  |        patrickdeelen@gmail.com        |\n"
			+ "  |                                       |\n"
			+ "  |     Genomics Coordination Center      |\n"
			+ "  |        Department of Genetics         |\n"
			+ "  |  University Medical Center Groningen  |\n"
			+ "  \\---------------------------------------/";
	private static final Options OPTIONS;
	private static Logger LOGGER;
	private static final int[] EXTRA_COL_FROM_REPLICATION;

	private static final int REPLICATION_SNP_CHR_COL = 16;
	private static final int REPLICATION_SNP_POS_COL = 17;
	private static final int REPLICATION_GENE_COL = 2;
	private static final int REPLICATION_BETA_COL = 23;
	private static final int REPLICATION_ALLELES_COL = 21;
	private static final int REPLICATION_ALLELE_ASSESSED_COL = 22;

	static {

		EXTRA_COL_FROM_REPLICATION = new int[13];
		for (int i = 3; i <= 14; ++i) {
			EXTRA_COL_FROM_REPLICATION[i - 3] = i;
		}
		EXTRA_COL_FROM_REPLICATION[12] = 0;

		LOGGER = Logger.getLogger(GenotypeInfo.class);

		OPTIONS = new Options();

		OptionBuilder.withArgName("basePath");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("Genotypes for LD calculations");
		OptionBuilder.withLongOpt("genotypes");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create("g"));

		OptionBuilder.withArgName("type");
		OptionBuilder.hasArg();
		OptionBuilder.withDescription("The input data type. If not defined will attempt to automatically select the first matching dataset on the specified path\n"
				+ "* PED_MAP - plink PED MAP files.\n"
				+ "* PLINK_BED - plink BED BIM FAM files.\n"
				+ "* VCF - bgziped vcf with tabix index file\n"
				+ "* VCFFOLDER - matches all bgziped vcf files + tabix index in a folder\n"
				+ "* SHAPEIT2 - shapeit2 phased haplotypes .haps & .sample\n"
				+ "* GEN - Oxford .gen & .sample\n"
				+ "* TRITYPER - TriTyper format folder");
		OptionBuilder.withLongOpt("genotypesFormat");
		OPTIONS.addOption(OptionBuilder.create("G"));

		OptionBuilder.withArgName("path");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("Path to output file");
		OptionBuilder.withLongOpt("output");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create('o'));

		OptionBuilder.withArgName("path");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("Path to replication eQTL file");
		OptionBuilder.withLongOpt("eqtls");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create('e'));

		OptionBuilder.withArgName("path");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("File with interaction QTLs");
		OptionBuilder.withLongOpt("interactions");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create('i'));

		OptionBuilder.withArgName("double");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("LD cutoff");
		OptionBuilder.withLongOpt("ld");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create("ld"));

		OptionBuilder.withArgName("int");
		OptionBuilder.hasArgs();
		OptionBuilder.withDescription("window");
		OptionBuilder.withLongOpt("window");
		OptionBuilder.isRequired();
		OPTIONS.addOption(OptionBuilder.create("w"));

	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) throws IOException, LdCalculatorException {

		System.out.println(HEADER);
		System.out.println();
		System.out.flush(); //flush to make sure header is before errors
		try {
			Thread.sleep(25); //Allows flush to complete
		} catch (InterruptedException ex) {
		}

		CommandLineParser parser = new PosixParser();
		final CommandLine commandLine;
		try {
			commandLine = parser.parse(OPTIONS, args, true);
		} catch (ParseException ex) {
			System.err.println("Invalid command line arguments: " + ex.getMessage());
			System.err.println();
			new HelpFormatter().printHelp(" ", OPTIONS);
			System.exit(1);
			return;
		}

		final String[] genotypesBasePaths = commandLine.getOptionValues("g");
		final RandomAccessGenotypeDataReaderFormats genotypeDataType;
		final String replicationQtlFilePath = commandLine.getOptionValue("e");
		final String interactionQtlFilePath = commandLine.getOptionValue("i");
		final String outputFilePath = commandLine.getOptionValue("o");
		final double ldCutoff = Double.parseDouble(commandLine.getOptionValue("ld"));
		final int window = Integer.parseInt(commandLine.getOptionValue("w"));

		System.out.println("Genotype: " + Arrays.toString(genotypesBasePaths));
		System.out.println("Interaction file: " + interactionQtlFilePath);
		System.out.println("Replication file: " + replicationQtlFilePath);
		System.out.println("Output: " + outputFilePath);
		System.out.println("LD: " + ldCutoff);
		System.out.println("Window: " + window);

		try {
			if (commandLine.hasOption("G")) {
				genotypeDataType = RandomAccessGenotypeDataReaderFormats.valueOf(commandLine.getOptionValue("G").toUpperCase());
			} else {
				if (genotypesBasePaths[0].endsWith(".vcf")) {
					System.err.println("Only vcf.gz is supported. Please see manual on how to do create a vcf.gz file.");
					System.exit(1);
					return;
				}
				try {
					genotypeDataType = RandomAccessGenotypeDataReaderFormats.matchFormatToPath(genotypesBasePaths[0]);
				} catch (GenotypeDataException e) {
					System.err.println("Unable to determine input 1 type based on specified path. Please specify -G");
					System.exit(1);
					return;
				}
			}
		} catch (IllegalArgumentException e) {
			System.err.println("Error parsing --genotypesFormat \"" + commandLine.getOptionValue("G") + "\" is not a valid input data format");
			System.exit(1);
			return;
		}

		final RandomAccessGenotypeData genotypeData;

		try {
			genotypeData = genotypeDataType.createFilteredGenotypeData(genotypesBasePaths, 100, null, null, null, 0.8);
		} catch (TabixFileNotFoundException e) {
			LOGGER.fatal("Tabix file not found for input data at: " + e.getPath() + "\n"
					+ "Please see README on how to create a tabix file");
			System.exit(1);
			return;
		} catch (IOException e) {
			LOGGER.fatal("Error reading input data: " + e.getMessage(), e);
			System.exit(1);
			return;
		} catch (IncompatibleMultiPartGenotypeDataException e) {
			LOGGER.fatal("Error combining the impute genotype data files: " + e.getMessage(), e);
			System.exit(1);
			return;
		} catch (GenotypeDataException e) {
			LOGGER.fatal("Error reading input data: " + e.getMessage(), e);
			System.exit(1);
			return;
		}

		ChrPosTreeMap<ArrayList<ReplicationQtl>> replicationQtls = new ChrPosTreeMap<>();

		CSVReader replicationQtlReader = new CSVReader(new FileReader(replicationQtlFilePath), '\t');
		String[] replicationHeader = replicationQtlReader.readNext();
		String[] replicationLine;
		while ((replicationLine = replicationQtlReader.readNext()) != null) {

			try {

				GeneticVariant variant = genotypeData.getSnpVariantByPos(replicationLine[REPLICATION_SNP_CHR_COL], Integer.parseInt(replicationLine[REPLICATION_SNP_POS_COL]));
				if (variant == null) {
					continue;
				}
				
				Alleles variantAlleles = variant.getVariantAlleles();
				String[] replicationAllelesString = StringUtils.split(replicationLine[REPLICATION_ALLELES_COL], '/');
				
				
				Alleles replicationAlleles = Alleles.createBasedOnString(replicationAllelesString[0], replicationAllelesString[1]);
				Allele assessedAlleleReplication = Allele.create(replicationLine[REPLICATION_ALLELE_ASSESSED_COL]);
				
				boolean isAmbigous = replicationAlleles.isAtOrGcSnp();
				
				if(!variantAlleles.equals(replicationAlleles)){
					if(variantAlleles.equals(replicationAlleles.getComplement())){
						assessedAlleleReplication = assessedAlleleReplication.getComplement();
					} else {
						continue;
					}
				} 

				ReplicationQtl replicationQtl = new ReplicationQtl(replicationLine[REPLICATION_SNP_CHR_COL], Integer.parseInt(replicationLine[REPLICATION_SNP_POS_COL]), replicationLine[REPLICATION_GENE_COL], Double.parseDouble(replicationLine[REPLICATION_BETA_COL]), assessedAlleleReplication.getAlleleAsString(), replicationLine, isAmbigous);
				ArrayList<ReplicationQtl> posReplicationQtls = replicationQtls.get(replicationQtl.getChr(), replicationQtl.getPos());
				if (posReplicationQtls == null) {
					posReplicationQtls = new ArrayList<>();
					replicationQtls.put(replicationQtl.getChr(), replicationQtl.getPos(), posReplicationQtls);
				}
				posReplicationQtls.add(replicationQtl);

			} catch (Exception e) {
				System.out.println(Arrays.toString(replicationLine));
				throw e;
			}
		}

		int interactionSnpNotInGenotypeData = 0;
		int noReplicationQtlsInWindow = 0;
		int noReplicationQtlsInLd = 0;
		int multipleReplicationQtlsInLd = 0;
		int replicationTopSnpNotInGenotypeData = 0;

		final CSVWriter outputWriter = new CSVWriter(new FileWriter(new File(outputFilePath)), '\t', '\0');
		final String[] outputLine = new String[15 + EXTRA_COL_FROM_REPLICATION.length];
		int c = 0;
		outputLine[c++] = "Chr";
		outputLine[c++] = "Pos";
		outputLine[c++] = "SNP";
		outputLine[c++] = "Gene";
		outputLine[c++] = "Module";
		outputLine[c++] = "DiscoveryZ";
		outputLine[c++] = "ReplicationZ";
		outputLine[c++] = "DiscoveryZCorrected";
		outputLine[c++] = "ReplicationZCorrected";
		outputLine[c++] = "DiscoveryAlleleAssessed";
		outputLine[c++] = "ReplicationAlleleAssessed";
		outputLine[c++] = "bestLd";
		outputLine[c++] = "bestLd_dist";
		outputLine[c++] = "nextLd";
		outputLine[c++] = "replicationAmbigous";
		for (int i = 0; i < EXTRA_COL_FROM_REPLICATION.length; ++i) {
			outputLine[c++] = replicationHeader[EXTRA_COL_FROM_REPLICATION[i]];
		}
		outputWriter.writeNext(outputLine);

		HashSet<String> notFound = new HashSet<>();

		CSVReader interactionQtlReader = new CSVReader(new FileReader(interactionQtlFilePath), '\t');
		interactionQtlReader.readNext();//skip header
		String[] interactionQtlLine;
		while ((interactionQtlLine = interactionQtlReader.readNext()) != null) {

			String snp = interactionQtlLine[1];
			String chr = interactionQtlLine[2];
			int pos = Integer.parseInt(interactionQtlLine[3]);
			String gene = interactionQtlLine[4];
			String alleleAssessed = interactionQtlLine[9];
			String module = interactionQtlLine[12];
			double discoveryZ = Double.parseDouble(interactionQtlLine[10]);

			GeneticVariant interactionQtlVariant = genotypeData.getSnpVariantByPos(chr, pos);

			if (interactionQtlVariant == null) {
				System.err.println("Interaction QTL SNP not found in genotype data: " + chr + ":" + pos);
				++interactionSnpNotInGenotypeData;
				continue;
			}

			ReplicationQtl bestMatch = null;
			double bestMatchR2 = Double.NaN;
			Ld bestMatchLd = null;
			double nextBestR2 = Double.NaN;

			ArrayList<ReplicationQtl> sameSnpQtls = replicationQtls.get(chr, pos);

			if (sameSnpQtls != null) {
				for (ReplicationQtl sameSnpQtl : sameSnpQtls) {
					if (sameSnpQtl.getGene().equals(gene)) {
						bestMatch = sameSnpQtl;
						bestMatchR2 = 1;
					}
				}
			}

			NavigableMap<Integer, ArrayList<ReplicationQtl>> potentionalReplicationQtls = replicationQtls.getChrRange(chr, pos - window, true, pos + window, true);

			for (ArrayList<ReplicationQtl> potentialReplicationQtls : potentionalReplicationQtls.values()) {

				for (ReplicationQtl potentialReplicationQtl : potentialReplicationQtls) {

					if (!potentialReplicationQtl.getGene().equals(gene)) {
						continue;
					}

					GeneticVariant potentialReplicationQtlVariant = genotypeData.getSnpVariantByPos(potentialReplicationQtl.getChr(), potentialReplicationQtl.getPos());

					if (potentialReplicationQtlVariant == null) {
						notFound.add(potentialReplicationQtl.getChr() + ":" + potentialReplicationQtl.getPos());
						++replicationTopSnpNotInGenotypeData;
						continue;
					}

					Ld ld = interactionQtlVariant.calculateLd(potentialReplicationQtlVariant);
					double r2 = ld.getR2();

					if (r2 > 1) {
						r2 = 1;
					}

					if (bestMatch == null) {
						bestMatch = potentialReplicationQtl;
						bestMatchR2 = r2;
						bestMatchLd = ld;
					} else if (r2 > bestMatchR2) {
						bestMatch = potentialReplicationQtl;
						nextBestR2 = bestMatchR2;
						bestMatchR2 = r2;
						bestMatchLd = ld;
					}

				}
			}

			double replicationZ = Double.NaN;
			double replicationZCorrected = Double.NaN;
			double discoveryZCorrected = Double.NaN;

			String replicationAlleleAssessed = null;

			if (bestMatch != null) {
				replicationZ = bestMatch.getBeta();
				replicationAlleleAssessed = bestMatch.getAssessedAllele();

				if (pos != bestMatch.getPos()) {

					String commonHap = null;
					double commonHapFreq = -1;
					for (Map.Entry<String, Double> hapFreq : bestMatchLd.getHaplotypesFreq().entrySet()) {

						double f = hapFreq.getValue();

						if (f > commonHapFreq) {
							commonHapFreq = f;
							commonHap = hapFreq.getKey();
						}

					}

					String[] commonHapAlleles = StringUtils.split(commonHap, '/');

					
			
					discoveryZCorrected = commonHapAlleles[0].equals(alleleAssessed) ? discoveryZ : discoveryZ * -1;
					replicationZCorrected = commonHapAlleles[1].equals(replicationAlleleAssessed) ? replicationZ : replicationZ * -1;

				} else {

					discoveryZCorrected = discoveryZ;
					replicationZCorrected = alleleAssessed.equals(replicationAlleleAssessed) ? replicationZ : replicationZ * -1;
					//replicationZCorrected = alleleAssessed.equals(replicationAlleleAssessed) || alleleAssessed.equals(String.valueOf(Utils.getComplementNucleotide(replicationAlleleAssessed.charAt(0)))) ? replicationZ : replicationZ * -1;

				}

			}

			c = 0;
			outputLine[c++] = chr;
			outputLine[c++] = String.valueOf(pos);
			outputLine[c++] = snp;
			outputLine[c++] = gene;
			outputLine[c++] = module;
			outputLine[c++] = String.valueOf(discoveryZ);
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(replicationZ);
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(discoveryZCorrected);
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(replicationZCorrected);
			outputLine[c++] = alleleAssessed;
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(bestMatch.getAssessedAllele());
			outputLine[c++] = String.valueOf(bestMatchR2);
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(Math.abs(pos - bestMatch.getPos()));
			outputLine[c++] = String.valueOf(nextBestR2);
			outputLine[c++] = bestMatch == null ? "NA" : String.valueOf(bestMatch.isIsAmbigous());

			if (bestMatch == null) {
				for (int i = 0; i < EXTRA_COL_FROM_REPLICATION.length; ++i) {
					outputLine[c++] = "NA";
				}
			} else {
				for (int i = 0; i < EXTRA_COL_FROM_REPLICATION.length; ++i) {
					outputLine[c++] = bestMatch.getLine()[EXTRA_COL_FROM_REPLICATION[i]];
				}
			}

			outputWriter.writeNext(outputLine);

		}

		outputWriter.close();

		for (String e : notFound) {
			System.err.println("Not found: " + e);
		}

		System.out.println("interactionSnpNotInGenotypeData: " + interactionSnpNotInGenotypeData);
		System.out.println("noReplicationQtlsInWindow: " + noReplicationQtlsInWindow);
		System.out.println("noReplicationQtlsInLd: " + noReplicationQtlsInLd);
		System.out.println("multipleReplicationQtlsInLd: " + multipleReplicationQtlsInLd);
		System.out.println("replicationTopSnpNotInGenotypeData: " + replicationTopSnpNotInGenotypeData);

	}

	private static class ReplicationQtl {

		private final String chr;
		private final int pos;
		private final String gene;
		private final double beta;
		private final String assessedAllele;
		private final String[] line;
		private final boolean isAmbigous;

		public ReplicationQtl(String chr, int pos, String gene, double beta, String assessedAllele, String[] line, boolean isAmbigous) {
			this.chr = chr;
			this.pos = pos;
			this.gene = gene;
			this.beta = beta;
			this.assessedAllele = assessedAllele;
			this.line = line;
			this.isAmbigous = isAmbigous;
		}

		public String getAssessedAllele() {
			return assessedAllele;
		}

		public String getChr() {
			return chr;
		}

		public int getPos() {
			return pos;
		}

		public String getGene() {
			return gene;
		}

		public double getBeta() {
			return beta;
		}

		public String[] getLine() {
			return line;
		}

		public boolean isIsAmbigous() {
			return isAmbigous;
		}

	}

}
