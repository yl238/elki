package de.lmu.ifi.dbs.preprocessing;

import de.lmu.ifi.dbs.data.RealVector;
import de.lmu.ifi.dbs.database.AssociationID;
import de.lmu.ifi.dbs.database.Database;
import de.lmu.ifi.dbs.distance.Distance;
import de.lmu.ifi.dbs.distance.similarityfunction.kernel.ArbitraryKernelFunctionWrapper;
import de.lmu.ifi.dbs.distance.similarityfunction.kernel.LinearKernelFunction;
import de.lmu.ifi.dbs.utilities.QueryResult;
import de.lmu.ifi.dbs.utilities.Util;
import de.lmu.ifi.dbs.utilities.optionhandling.DoubleParameter;
import de.lmu.ifi.dbs.utilities.optionhandling.Flag;
import de.lmu.ifi.dbs.utilities.optionhandling.OptionHandler;
import de.lmu.ifi.dbs.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.utilities.optionhandling.Parameter;
import de.lmu.ifi.dbs.utilities.optionhandling.ParameterException;
import de.lmu.ifi.dbs.utilities.optionhandling.WrongParameterValueException;
import de.lmu.ifi.dbs.utilities.optionhandling.constraints.GlobalParameterConstraint;
import de.lmu.ifi.dbs.utilities.optionhandling.constraints.GreaterEqualConstraint;
import de.lmu.ifi.dbs.utilities.optionhandling.constraints.LessEqualConstraint;
import de.lmu.ifi.dbs.utilities.optionhandling.constraints.ParameterConstraint;
import de.lmu.ifi.dbs.utilities.optionhandling.constraints.ParameterFlagGlobalConstraint;
import de.lmu.ifi.dbs.varianceanalysis.CompositeEigenPairFilter;
import de.lmu.ifi.dbs.varianceanalysis.LimitEigenPairFilter;
import de.lmu.ifi.dbs.varianceanalysis.LocalKernelPCA;
import de.lmu.ifi.dbs.varianceanalysis.LocalPCA;
import de.lmu.ifi.dbs.varianceanalysis.NormalizingEigenPairFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Preprocessor for kernel 4C local dimensionality, neighbor objects and strong
 * eigenvector matrix assignment to objects of a certain database.
 *
 * @author Simon Paradies
 */
public class KernelFourCPreprocessor<D extends Distance<D>, V extends RealVector<V, ?>> extends ProjectedDBSCANPreprocessor<D, V> {

    /**
     * The default kernel function class name.
     */
    public static final String DEFAULT_KERNEL_FUNCTION_CLASS = LinearKernelFunction.class.getName();

    /**
     * Parameter for preprocessor.
     */
    public static final String KERNEL_FUNCTION_CLASS_P = "kernel";

    /**
     * Description for parameter preprocessor.
     */
    public static final String KERNEL_FUNCTION_CLASS_D = "the kernel function which is used to compute the epsilon neighborhood."
        + "Default: " + DEFAULT_KERNEL_FUNCTION_CLASS;

    /**
     * Flag for marking parameter delta as an absolute value.
     */
    public static final String ABSOLUTE_F = LimitEigenPairFilter.ABSOLUTE_F;

    /**
     * Description for flag abs.
     */
    public static final String ABSOLUTE_D = LimitEigenPairFilter.ABSOLUTE_D;

    /**
     * Option string for parameter delta.
     */
    public static final String DELTA_P = LimitEigenPairFilter.DELTA_P;

    /**
     * Description for parameter delta.
     */
    public static final String DELTA_D = LimitEigenPairFilter.DELTA_D;

    /**
     * The default value for delta.
     */
    public static final double DEFAULT_DELTA = 0.1;

    /**
     * Threshold for strong eigenpairs, can be absolute or relative.
     */
    private double delta;

    /**
     * Indicates wether delta is an absolute or a relative value.
     */
    private boolean absolute;

    /**
     * The parameter settings for the PCA.
     */
    private String[] pcaParameters;

    /**
     *
     */
    public KernelFourCPreprocessor() {
        super();
        // parameter delta
        // parameter constraints are only valid if delta is a relative value! Thus they are
        // dependent on the absolute flag, that is they are global constraints!
        final ArrayList<ParameterConstraint> deltaCons = new ArrayList<ParameterConstraint>();
        deltaCons.add(new GreaterEqualConstraint(0));
        deltaCons.add(new LessEqualConstraint(1));

        final DoubleParameter delta = new DoubleParameter(DELTA_P, DELTA_D);
        delta.setDefaultValue(DEFAULT_DELTA);
        optionHandler.put(delta);

        // parameter absolute flag
        Flag abs = new Flag(ABSOLUTE_F, ABSOLUTE_D);
        optionHandler.put(abs);

        GlobalParameterConstraint gpc = new ParameterFlagGlobalConstraint(delta, deltaCons, abs, false);
        optionHandler.setGlobalParameterConstraint(gpc);
    }

    /**
     * This method implements the type of variance analysis to be computed for a
     * given point. <p/> Example1: for 4C, this method should implement a PCA
     * for the given point. Example2: for PreDeCon, this method should implement
     * a simple axis-parallel variance analysis.
     *
     * @param id        the given point
     * @param neighbors the neighbors as query results of the given point
     * @param database  the database for which the preprocessing is performed
     */
    @Override
    protected void runVarianceAnalysis(final Integer id, final List<QueryResult<D>> neighbors, final Database<V> database) {
        final LocalKernelPCA<V> pca = new LocalKernelPCA<V>();
        try {
            pca.setParameters(pcaParameters);
        }
        catch (final ParameterException e) {
            // tested before
            throw new RuntimeException("This should never happen!");
        }
        final List<Integer> ids = new ArrayList<Integer>(neighbors.size());
        for (final QueryResult<D> neighbor : neighbors) {
            ids.add(neighbor.getID());
        }
        pca.run(ids, database);

        if (debug) {
            final StringBuffer msg = new StringBuffer();
            msg.append("\n").append(id).append(" ").append(database.getAssociation(AssociationID.LABEL, id));
            msg.append("\ncorrDim ").append(pca.getCorrelationDimension());
            debugFine(msg.toString());
        }
        database.associate(AssociationID.LOCAL_DIMENSIONALITY, id, pca.getCorrelationDimension());
        database.associate(AssociationID.STRONG_EIGENVECTOR_MATRIX, id, pca.getStrongEigenvectors());
        database.associate(AssociationID.NEIGHBORS, id, ids);
    }

    /**
     * Sets the values for the parameters alpha, pca and pcaDistancefunction if
     * specified. If the parameters are not specified default values are set.
     *
     * @see de.lmu.ifi.dbs.utilities.optionhandling.Parameterizable#setParameters(String[])
     */
    @Override
    public String[] setParameters(final String[] args) throws ParameterException {
        // add the kernel fucntion wrapper for the distance function
        String[] preprocessorParameters = new String[args.length + 2];
        System.arraycopy(args, 0, preprocessorParameters, 2, args.length);
        preprocessorParameters[0] = OptionHandler.OPTION_PREFIX + ProjectedDBSCANPreprocessor.DISTANCE_FUNCTION_P;
        preprocessorParameters[1] = ArbitraryKernelFunctionWrapper.class.getName();
        final String[] remainingParameters = super.setParameters(preprocessorParameters);
        // absolute
        absolute = optionHandler.isSet(ABSOLUTE_F);

        // delta
        delta = (Double) optionHandler.getOptionValue(DELTA_P);
        if (absolute && ((Parameter) optionHandler.getOption(DELTA_P)).tookDefaultValue()) {
            throw new WrongParameterValueException("Illegal parameter setting: " + "Flag " + ABSOLUTE_F + " is set, " + "but no value for "
                + DELTA_P + " is specified.");
        }

        final LocalKernelPCA<V> tmpPCA = new LocalKernelPCA<V>();
        // save parameters for pca
        final List<String> tmpPCAParameters = new ArrayList<String>();
        // eigen pair filter
        Util.addParameter(tmpPCAParameters, OptionID.PCA_EIGENPAIR_FILTER, CompositeEigenPairFilter.class.getName());
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + CompositeEigenPairFilter.FILTERS_P);
        tmpPCAParameters.add(LimitEigenPairFilter.class.getName() + CompositeEigenPairFilter.COMMA_SPLIT
            + NormalizingEigenPairFilter.class.getName() + CompositeEigenPairFilter.COMMA_SPLIT + LimitEigenPairFilter.class.getName());
        // parameters for eigenpair filtering
        // eleminate eigenpairs with eigenvalue < 0.0
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LimitEigenPairFilter.ABSOLUTE_F);
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LimitEigenPairFilter.DELTA_P);
        tmpPCAParameters.add("0.0");
        // separate in strong and weak (delta)
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LimitEigenPairFilter.DELTA_P);
        tmpPCAParameters.add(Double.toString(delta));
        // abs
        if (absolute) {
            tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LimitEigenPairFilter.ABSOLUTE_F);
        }

        // Big and small are not used in this version of KernelFourC
        // as they implicitly take the values 1 (big) and 0 (small),
        // big value
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LocalPCA.BIG_VALUE_P);
        tmpPCAParameters.add("1");
        // small value
        tmpPCAParameters.add(OptionHandler.OPTION_PREFIX + LocalPCA.SMALL_VALUE_P);
        tmpPCAParameters.add("0");

        pcaParameters = tmpPCAParameters.toArray(new String[tmpPCAParameters.size()]);
        tmpPCA.setParameters(pcaParameters);
        setParameters(args, remainingParameters);

        return remainingParameters;
    }

    /**
     * @see de.lmu.ifi.dbs.utilities.optionhandling.Parameterizable#description()
     */
    @Override
    public String description() {
        final StringBuffer description = new StringBuffer();
        description.append(KernelFourCPreprocessor.class.getName());
        description
            .append(" computes the local dimensionality and locally weighted matrix of objects of a certain database according to the 4C algorithm.\n");
        description.append("The PCA is based on epsilon range queries.\n");
        description.append(optionHandler.usage("", false));
        return description.toString();
    }

}