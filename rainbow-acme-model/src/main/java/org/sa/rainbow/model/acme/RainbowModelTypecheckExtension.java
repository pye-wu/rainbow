package org.sa.rainbow.model.acme;

import org.acmestudio.acme.core.extension.IAcmeElementExtension;

/**
 * Used by the typechecking analyser to indicate whether the model typechecks or not.
 * 
 * @author Bradley Schmerl: schmerl
 * 
 */
public class RainbowModelTypecheckExtension implements IAcmeElementExtension {

    protected boolean m_typechecks;

    public RainbowModelTypecheckExtension (boolean typechecks) {
        m_typechecks = typechecks;
    }

    public boolean typechecks () {
        return m_typechecks;
    }

    @Override
    public boolean isDistinctInDerivedViews () {
        return false;
    }

    @Override
    public boolean isPersistent () {
        return false;
    }

    @Override
    public Object clone () throws CloneNotSupportedException {
        return super.clone ();
    }

}