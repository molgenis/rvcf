package org.molgenis.data.annotation.makervcf.structs;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.molgenis.data.annotation.core.entity.impl.snpeff.Impact;
import org.molgenis.data.vcf.datastructures.Sample;
import org.molgenis.vcf.VcfInfo;
import org.molgenis.vcf.VcfRecord;
import org.molgenis.vcf.VcfRecordUtils;
import org.molgenis.vcf.meta.VcfMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link VcfRecord} annotated with SnpEff, CADD (as much as possible), ExAC, GoNL and 1000G.
 */
public class AnnotatedVcfRecord extends VcfRecord
{
	private static final Logger LOG = LoggerFactory.getLogger(AnnotatedVcfRecord.class);

	private static final String EXAC_AF = "EXAC_AF";
	private static final String GO_NL_AF = "GoNL_AF";
	private static final String CLSF = "CLSF";
	private static final String ANN = "ANN";
	private static final String RLV = "RLV";
	private static final String REPORTEDPATHOGENIC = "REPORTEDPATHOGENIC";
	public static final String CADD_SCALED = "CADD_SCALED";

	public AnnotatedVcfRecord(VcfRecord record)
	{
		super(record.getVcfMeta(), record.getTokens());
	}

	double getExAcAlleleFrequencies(int i)
	{
		Double[] alleleFrequencies = VcfRecordUtils.getAltAlleleOrderedDoubleField(this, EXAC_AF);
		return alleleFrequencies[i] != null ? alleleFrequencies[i] : 0;
	}

	double getGoNlAlleleFrequencies(int i)
	{
		Double[] alleleFrequencies = VcfRecordUtils.getAltAlleleOrderedDoubleField(this, GO_NL_AF);
		return alleleFrequencies[i] != null ? alleleFrequencies[i] : 0;
	}

	public Optional<String> getClsf()
	{
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(CLSF, this);
		return optionalVcfInfo.map(vcfInfo -> (String) vcfInfo.getVal());
	}

	Set<String> getGenesFromAnn()
	{
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(ANN, this);
		return optionalVcfInfo.map(vcfInfo ->
		{
			String ann = vcfInfo.getValRaw();
			Set<String> genes = new HashSet<>();
			String[] annSplit = ann.split(",", -1);
			for (String oneAnn : annSplit)
			{
				String[] fields = oneAnn.split("\\|", -1);
				String gene = fields[3];
				genes.add(gene);
			}
			return genes;

		}).orElse(emptySet());
	}

	Optional<Impact> getImpact(int i, String gene)
	{
		String allele = VcfRecordUtils.getAltsAsStringArray(this)[i];
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(ANN, this);
		return optionalVcfInfo.map(vcfInfo -> getImpact(vcfInfo.getValRaw(), gene, allele));
	}

	Optional<String> getTranscript(int i, String gene)
	{
		String allele = VcfRecordUtils.getAltsAsStringArray(this)[i];
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(ANN, this);
		return optionalVcfInfo.map(vcfInfo -> getTranscript(vcfInfo.getValRaw(), gene, allele));
	}

	public List<RVCF> getRvcf()
	{
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(RLV, this);
		return optionalVcfInfo.map(RVCF::fromVcfInfo).orElse(emptyList());
	}

	/**
	 * @return phred scores (can contain null values)
	 */
	Double[] getCaddPhredScores()
	{
		return VcfRecordUtils.getAltAlleleOrderedDoubleField(this, CADD_SCALED);
	}

	public Optional<String> getReportedPathogenic()
	{
		Optional<VcfInfo> optionalVcfInfo = VcfRecordUtils.getInformation(REPORTEDPATHOGENIC, this);
		return optionalVcfInfo.map(vcfInfo -> (String) vcfInfo.getVal());
	}

	private static Impact getImpact(String ann, String gene, String allele)
	{
		//get the right annotation entry that matches both gene and allele
		String findAnn = getAnn(ann, gene, allele);
		if (findAnn == null)
		{
			LOG.warn("failed to get impact for gene '{}', allele '{}' in {}", gene, allele, ann);
			return null;
		}
		else
		{
			//from the right one, get the impact
			String[] fields = findAnn.split("\\|", -1);
			String impact = fields[2];
			return Impact.valueOf(impact);
		}
	}

	private static String getTranscript(String ann, String gene, String allele)
	{
		//get the right annotation entry that matches both gene and allele
		String findAnn = getAnn(ann, gene, allele);
		if (findAnn == null)
		{
			LOG.warn("failed to get impact for gene '{}', allele '{}' in {}", gene, allele, ann);
			return null;
		}
		else
		{
			//from the right one, get the impact
			String[] fields = findAnn.split("\\|", -1);
			return fields[6];//fields[6] == transcript
		}
	}

	private static String getAnn(String ann, String gene, String allele)
	{
		String[] annSplit = ann.split(",", -1);
		for (String oneAnn : annSplit)
		{
			String[] fields = oneAnn.split("\\|", -1);
			String geneFromAnn = fields[3];
			if (gene.equals(geneFromAnn))
			{
				String alleleFromAnn = fields[0];
				if (allele.equals(alleleFromAnn))
				{
					return oneAnn;
				}
			}
		}
		LOG.warn("annotation could not be found for {}, allele={}, ann={}", gene, allele, ann);
		return null;
	}

  public String[] getSampleTokens() {
    int firstSample = VcfMeta.COL_FORMAT_IDX + 1;
    return Arrays.copyOfRange(getTokens(), firstSample, firstSample + getNrSamples());
  }

  public static Stream<Sample> toSamples(VcfRecord vcfRecord) {
    AtomicInteger counter = new AtomicInteger(0);
    VcfMeta vcfMeta = vcfRecord.getVcfMeta();
    return StreamSupport.stream(vcfRecord.getSamples().spliterator(), false).map(vcfSample ->
    {
      String sampleName = vcfMeta.getSampleName(counter.getAndIncrement());
      String genoType = VcfRecordUtils.getSampleFieldValue(vcfRecord, vcfSample, "GT");
      String doubleString = VcfRecordUtils.getSampleFieldValue(vcfRecord, vcfSample, "DP");
      Double depth = doubleString != null ? Double.parseDouble(doubleString) : null;

      return new Sample(sampleName, genoType, depth);
    });
  }
}
