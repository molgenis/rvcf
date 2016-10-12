package org.molgenis.data.annotation.reportrvcf;

import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.commons.collections.map.MultiKeyMap;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.broadinstitute.variant.vcf.VCFUtils;
import org.molgenis.cgd.CGDEntry;
import org.molgenis.cgd.LoadCGD;
import org.molgenis.data.Entity;
import org.molgenis.data.annotation.makervcf.positionalstream.MatchVariantsToGenotypeAndInheritance;
import org.molgenis.data.annotation.makervcf.structs.RVCF;
import org.molgenis.data.annotation.makervcf.structs.VcfEntity;
import org.molgenis.data.vcf.VcfRepository;
import org.molgenis.data.vcf.utils.VcfUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * Created by joeri on 10/12/16.
 */
public class MultiPhenotypeCohortAnalysis {

    File rvcfInputFile;
    File outputZscoreFile;
    File cgdFile;



    public static void main(String[] args) throws Exception {
        MultiPhenotypeCohortAnalysis mdca = new MultiPhenotypeCohortAnalysis(new File(args[0]), new File(args[1]), new File(args[2]));
        mdca.start();
    }

    public MultiPhenotypeCohortAnalysis(File rvcfInputFile, File outputZscoreFile, File cgdFile)
    {
        this.rvcfInputFile = rvcfInputFile;
        this.outputZscoreFile = outputZscoreFile;
        this.cgdFile = cgdFile;
    }

    public void start() throws Exception {


        // meta data: number of individuals seen with this phenotype as denoted by PHENOTYPE in VCF: ##SAMPLE=<ID=P583292,SEX=UNKNOWN,PHENOTYPE=IRONACC>
        HashMap<String, String> individualsToPhenotype = new HashMap<>();

        // meta data: number of individuals seen with this phenotype as denoted by PHENOTYPE in VCF: ##SAMPLE=<ID=P583292,SEX=UNKNOWN,PHENOTYPE=IRONACC>
        HashMap<String, Integer> phenotypeToNrOfIndividuals = new HashMap<>();

        // meta data: first encounter of this gene, chromosome and bp position
        HashMap<String, String> geneToChromPos = new HashMap<>();

        // while iterating over rvcf, count affected individuals per gene for each phenotype
        MultiKeyMap geneAndPhenotypeToAffectedCount = new MultiKeyMap();

        // when done, we can get the fractions based on total counts and phenotypeToNrOfIndividuals
        MultiKeyMap geneAndPhenotypeToAffectedFraction = new MultiKeyMap();

        // transform fractions into Z-scores by comparing 1 phenotype group to the others
        MultiKeyMap geneAndPhenotypeToAffectedZscores = new MultiKeyMap();

        // execution
        phenotypeToNrOfIndividualsFromInputVcf(individualsToPhenotype, phenotypeToNrOfIndividuals, this.rvcfInputFile);
        System.out.println("individualsToPhenotype: " + individualsToPhenotype.toString());
        System.out.println("phenotypeToNrOfIndividuals: " + phenotypeToNrOfIndividuals.toString());

        countAffectedPerGene(geneToChromPos, individualsToPhenotype, geneAndPhenotypeToAffectedCount, this.rvcfInputFile);
        //System.out.println("countAffectedPerGene: " + geneAndPhenotypeToAffectedCount.toString());

        convertToFraction(phenotypeToNrOfIndividuals, geneAndPhenotypeToAffectedCount, geneAndPhenotypeToAffectedFraction);

        convertToZscore(geneAndPhenotypeToAffectedFraction, geneAndPhenotypeToAffectedZscores, phenotypeToNrOfIndividuals.keySet());

        printToOutput(geneToChromPos, geneAndPhenotypeToAffectedZscores, this.outputZscoreFile, phenotypeToNrOfIndividuals.keySet(), this.cgdFile);
    }



    public void phenotypeToNrOfIndividualsFromInputVcf( HashMap<String, String> individualsToPhenotype, HashMap<String, Integer> phenotypeToNrOfIndividuals, File rvcfInputFile) throws Exception
    {
        individualsToPhenotype.putAll(VcfUtils.getSampleToPhenotype(rvcfInputFile));
        HashMap<String, List<String>> phenotypeToSampleIDs = VcfUtils.getPhenotypeToSampleIDs(individualsToPhenotype);
        for(String phenotype : phenotypeToSampleIDs.keySet())
        {
            phenotypeToNrOfIndividuals.put(phenotype, phenotypeToSampleIDs.get(phenotype).size());
        }
    }


    public void countAffectedPerGene(HashMap<String, String> geneToChromPos, HashMap<String, String> individualsToPhenotype, MultiKeyMap geneAndPhenotypeToAffectedCount, File rvcfInputFile) throws Exception {
        VcfRepository vcf = new VcfRepository(rvcfInputFile, "vcf");
        Iterator<Entity> vcfIterator = vcf.iterator();

        // "SampleID_GeneName"
        Set<String> sampleAddedForGene = new HashSet<>();

        while(vcfIterator.hasNext()) {

            VcfEntity record = new VcfEntity(vcfIterator.next());

            for (RVCF rvcf : record.getRvcf())
            {
                String gene = rvcf.getGene();

                for(String sample : rvcf.getSampleStatus().keySet()) {

                    if(sampleAddedForGene.contains(sample+"_"+gene))
                    {
                        continue;
                    }

                    if (MatchVariantsToGenotypeAndInheritance.status.isPresumedAffected(rvcf.getSampleStatus().get(sample)))
                    //if (rvcf.getSampleStatus().get(sample).toString().contains("AFFECTED"))
                    {

                        String phenotype = individualsToPhenotype.get(sample);
                        Integer count = geneAndPhenotypeToAffectedCount.containsKey(gene, phenotype) ? (Integer)geneAndPhenotypeToAffectedCount.get(gene, phenotype) : 0;
                        count++;
                        geneAndPhenotypeToAffectedCount.put(gene, phenotype, count);

                        // make sure we count an individual only once per gene
                        sampleAddedForGene.add(sample+"_"+gene);

                        // add gene to meta data
                        if(!geneToChromPos.containsKey(gene))
                        {
                            geneToChromPos.put(gene, record.getChr() + "\t" + record.getPos());
                        }
                    }
                }

            }
        }

    }

    public void convertToFraction(HashMap<String, Integer> phenotypeToNrOfIndividuals, MultiKeyMap geneAndPhenotypeToAffectedCount, MultiKeyMap geneAndPhenotypeToAffectedFraction) throws Exception {
        for(Object o : geneAndPhenotypeToAffectedCount.keySet())
        {
            MultiKey key = (MultiKey)o;
            Integer nrAffected = (Integer)geneAndPhenotypeToAffectedCount.get(key);
            Integer nrOfIndividuals = phenotypeToNrOfIndividuals.get(key.getKey(1));
            double fraction = ((double)nrAffected/(double)nrOfIndividuals) * 100.0;
            if(fraction > 100.0)
            {
                throw new Exception("Fraction exceeds 100: " + fraction);
            }
            geneAndPhenotypeToAffectedFraction.put(key, fraction);
            //System.out.println("put: " + key + " " + nrAffected + ", nrOfIndividuals "+ nrOfIndividuals + " fraction = " + fraction);
        }
    }

    public void convertToZscore(MultiKeyMap geneAndPhenotypeToAffectedFraction, MultiKeyMap geneAndPhenotypeToAffectedZscores, Set<String> phenotypes)
    {
        for(Object o : geneAndPhenotypeToAffectedFraction.keySet())
        {
            MultiKey key = (MultiKey)o;
            double fractionAffected = (double)geneAndPhenotypeToAffectedFraction.get(key);
            String gene = (String)key.getKey(0);
            String phenotype = (String)key.getKey(1);

            System.out.println("gene: " + gene + ", phenotype: " + phenotype + ", fractionAffected: " + fractionAffected);

            int i = 0;
            double[] testAgainst = new double[phenotypes.size()-1];
            for(String phenotypeToTestAgainst : phenotypes)
            {
                if(phenotypeToTestAgainst.equals(phenotype))
                {
                    System.out.println("skipping self phenotype: " + phenotype+ " for gene " + gene);
                    continue;
                }

                Double fractionAffectedToTestAgainst = (Double)geneAndPhenotypeToAffectedFraction.get(gene, phenotypeToTestAgainst);
                fractionAffectedToTestAgainst = fractionAffectedToTestAgainst == null ? 0 : fractionAffectedToTestAgainst;

                testAgainst[i++] = fractionAffectedToTestAgainst;

                System.out.println("test against: " + phenotypeToTestAgainst + " for gene " + gene + ", fractionAffected: " + fractionAffectedToTestAgainst);

            }

            Mean meanEval = new Mean();
            double mean = meanEval.evaluate(testAgainst);

            StandardDeviation sdEval = new StandardDeviation();
            double sd = sdEval.evaluate(testAgainst);

            double zScore = (fractionAffected - mean) / sd;

            zScore = zScore == Double.POSITIVE_INFINITY ? 99 : zScore;

            System.out.println("mean: " + mean + ", sd: " + sd + ", Z-score: " + zScore);

            geneAndPhenotypeToAffectedZscores.put(gene, phenotype, zScore);


        }
    }

    public void printToOutput(HashMap<String, String> geneToChromPos, MultiKeyMap geneAndPhenotypeToAffectedZscores, File outputZscoreFile, Set<String> phenotypes, File cgdFile) throws IOException
    {

        Map<String, CGDEntry> cgd = LoadCGD.loadCGD(cgdFile);

        PrintWriter pw = new PrintWriter(outputZscoreFile);

        String phenotypeHeader = "";
        for(String p : phenotypes)
        {
            phenotypeHeader += "\t" + p;
        }

        pw.println("Gene" + "\t" + "Condition" + "\t" + "Chr" + "\t" + "Pos" + phenotypeHeader);

        for(String gene : geneToChromPos.keySet())
        {

            String condition = cgd.containsKey(gene) ? cgd.get(gene).getCondition() : "";

            StringBuffer zScores = new StringBuffer();
            for(String p : phenotypes)
            {
                if(geneAndPhenotypeToAffectedZscores.containsKey(gene, p))
                {
                    zScores.append("\t"+(double)geneAndPhenotypeToAffectedZscores.get(gene, p));

                }
                else
                {
                    zScores.append("\t"+"0");

                }
            }

            pw.println(gene + "\t" + condition + "\t" + geneToChromPos.get(gene) + zScores.toString());
        }

        pw.flush();
        pw.close();

    }


}
