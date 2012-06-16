/**
 *  LibraryProvider.java
 *  Copyright 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
 *  first published 01.10.2009 on http://yacy.net
 *
 *  This file is part of YaCy Content Integration
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.document;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import net.yacy.cora.document.MultiProtocolURI;
import net.yacy.cora.lod.JenaTripleStore;
import net.yacy.cora.lod.vocabulary.Tagging;
import net.yacy.cora.lod.vocabulary.Tagging.SOTuple;
import net.yacy.cora.storage.Files;
import net.yacy.document.geolocation.GeonamesLocation;
import net.yacy.document.geolocation.OpenGeoDBLocation;
import net.yacy.document.geolocation.OverarchingLocation;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.util.FileUtils;

import com.hp.hpl.jena.rdf.model.Resource;

public class LibraryProvider {

    public static final char tagPrefix = '$';
    public static final String path_to_source_dictionaries = "source";
    public static final String path_to_did_you_mean_dictionaries = "didyoumean";
    public static final String path_to_autotagging_dictionaries = "autotagging";

    public static final String disabledExtension = ".disabled";

    public static WordCache dymLib = new WordCache(null);
    public static Autotagging autotagging = null;
    public static OverarchingLocation geoLoc = new OverarchingLocation();
    private static File dictSource = null;
    private static File dictRoot = null;

    public static enum Dictionary {
        GEODB0(
            "geo0",
            "http://downloads.sourceforge.net/project/opengeodb/Data/0.2.5a/opengeodb-0.2.5a-UTF8-sql.gz" ),
        GEODB1( "geo1", "http://fa-technik.adfc.de/code/opengeodb/dump/opengeodb-02624_2011-10-17.sql.gz" ),
        GEON0( "geon0", "http://download.geonames.org/export/dump/cities1000.zip" ),
        DRW0( "drw0", "http://www.ids-mannheim.de/kl/derewo/derewo-v-100000t-2009-04-30-0.1.zip" ),
        PND0( "pnd0", "http://downloads.dbpedia.org/3.7-i18n/de/pnd_de.nt.bz2" );

        public String nickname, url, filename;

        private Dictionary(final String nickname, final String url) {
            try {
                this.filename = new MultiProtocolURI(url).getFileName();
            } catch ( final MalformedURLException e ) {
                assert false;
            }
            this.nickname = nickname;
            this.url = url;
        }

        public File file() {
            return new File(dictSource, this.filename);
        }

        public File fileDisabled() {
            return new File(dictSource, this.filename + disabledExtension);
        }
    }

    /**
     * initialize the LibraryProvider as static class. This assigns default paths, and initializes the
     * dictionary classes Additionally, if default dictionaries are given in the source path, they are
     * translated into the input format inside the DATA/DICTIONARIES directory
     *
     * @param pathToSource
     * @param pathToDICTIONARIES
     */
    public static void initialize(final File rootPath) {
        dictSource = new File(rootPath, path_to_source_dictionaries);
        if ( !dictSource.exists() ) {
            dictSource.mkdirs();
        }
        dictRoot = rootPath;

        // initialize libraries
        initAutotagging(tagPrefix);
        activateDeReWo();
        initDidYouMean();
        integrateOpenGeoDB();
        integrateGeonames();
        activatePND();
        Set<String> allTags = new HashSet<String>() ;
        allTags.addAll(autotagging.allTags()); // we must copy this into a clone to prevent circularity
        autotagging.addPlaces(geoLoc);
        //autotagging.addDictionaries(dymLib.getDictionaries()); // strange results with this: normal word lists are 'too full'
        WordCache.learn(allTags);
    }

    public static void integrateOpenGeoDB() {
        final File geo1 = Dictionary.GEODB1.file();
        final File geo0 = Dictionary.GEODB0.file();
        if ( geo1.exists() ) {
            if ( geo0.exists() ) {
                geo0.renameTo(Dictionary.GEODB0.fileDisabled());
            }
            geoLoc.activateLocalization(Dictionary.GEODB1.nickname, new OpenGeoDBLocation(geo1, false));
            return;
        }
        if ( geo0.exists() ) {
            geoLoc.activateLocalization(Dictionary.GEODB0.nickname, new OpenGeoDBLocation(geo0, false));
            return;
        }
    }

    public static void integrateGeonames() {
        final File geon = Dictionary.GEON0.file();
        if ( geon.exists() ) {
            geoLoc.activateLocalization(Dictionary.GEON0.nickname, new GeonamesLocation(geon));
            return;
        }
    }

    public static void initDidYouMean() {
        final File dymDict = new File(dictRoot, path_to_did_you_mean_dictionaries);
        if ( !dymDict.exists() ) {
            dymDict.mkdirs();
        }
        dymLib = new WordCache(dymDict);
    }

    public static void initAutotagging(char prefix) {
        final File autotaggingPath = new File(dictRoot, path_to_autotagging_dictionaries);
        if ( !autotaggingPath.exists() ) {
            autotaggingPath.mkdirs();
        }
        autotagging = new Autotagging(autotaggingPath, prefix);
    }

    public static void activateDeReWo() {
        // translate input files (once..)
        final File dymDict = new File(dictRoot, path_to_did_you_mean_dictionaries);
        if ( !dymDict.exists() ) {
            dymDict.mkdirs();
        }
        final File derewoInput = LibraryProvider.Dictionary.DRW0.file();
        final File derewoOutput = new File(dymDict, derewoInput.getName() + ".words");
        if ( !derewoOutput.exists() && derewoInput.exists() ) {
            // create the translation of the derewo file (which is easy in this case)
            final ArrayList<String> derewo = loadDeReWo(derewoInput, true);
            try {
                writeWords(derewoOutput, derewo);
            } catch ( final IOException e ) {
                Log.logException(e);
            }
        }
    }

    public static void deactivateDeReWo() {
        final File dymDict = new File(dictRoot, path_to_did_you_mean_dictionaries);
        final File derewoInput = LibraryProvider.Dictionary.DRW0.file();
        final File derewoOutput = new File(dymDict, derewoInput.getName() + ".words");
        FileUtils.deletedelete(derewoOutput);
    }

    public static void activatePND() {
        // translate input files (once..)
        final File dymDict = new File(dictRoot, path_to_did_you_mean_dictionaries);
        if ( !dymDict.exists() ) {
            dymDict.mkdirs();
        }
        // read the pnd file and store it into the triplestore
        final File dictInput = LibraryProvider.Dictionary.PND0.file();
        if ( dictInput.exists() ) {
            try {
            	JenaTripleStore.LoadNTriples(Files.read(dictInput));
            } catch ( final IOException e ) {
                Log.logException(e);
            }
        }
        // read the triplestore and generate a vocabulary
        Map<String, SOTuple> map = new HashMap<String, SOTuple>();
        Log.logInfo("LibraryProvider", "retrieving PND data from triplestore");
        Iterator<Resource> i = JenaTripleStore.getSubjects("http://dbpedia.org/ontology/individualisedPnd");
        Log.logInfo("LibraryProvider", "creating vocabulary map from PND triplestore");
        String objectspace = "";
        while (i.hasNext()) {
        	Resource resource = i.next();
        	String subject = resource.toString();

        	// prepare a propert term from the subject uri
        	int p = subject.lastIndexOf('/');
        	if (p < 0) continue;
        	String term = subject.substring(p + 1);
        	objectspace = subject.substring(0, p + 1);
        	p = term.indexOf('(');
        	if (p >= 0) term = term.substring(0, p);
        	term = term.replaceAll("_", " ").trim();
        	if (term.length() == 0) continue;

        	// store the term into the vocabulary map
        	map.put(term, new SOTuple("", subject));
        }
        try {
            Log.logInfo("LibraryProvider", "adding vocabulary to autotagging");
			Tagging pndVoc = new Tagging("Persons", null, objectspace, map);
			autotagging.addVocabulary(pndVoc);
            Log.logInfo("LibraryProvider", "added pnd vocabulary to autotagging");
		} catch (IOException e) {
		}
    }

    public static void deactivatePND() {
        // remove the PND Triples from the triplestore
    	JenaTripleStore.deleteObjects(null, "http://dbpedia.org/ontology/individualisedPnd");
    	autotagging.deleteVocabulary("Persons");
    }

    /*
    private static ArrayList<String> loadList(final File file, String comment, boolean toLowerCase) {
        final ArrayList<String> list = new ArrayList<String>();
        if (!file.exists()) return list;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if ((line.length() > 0) && (!(line.startsWith(comment)))) {
                    list.add((toLowerCase) ? line.trim().toLowerCase() : line.trim());
                }
            }
            reader.close();
        } catch (final IOException e) {
            Log.logException(e);
        } finally {
            if (reader != null) try { reader.close(); } catch (final Exception e) {}
        }
        return list;
    }
    */

    private static Set<String> sortUnique(final List<String> list) {
        final Set<String> s = new TreeSet<String>();
        for ( final String t : list ) {
            s.add(t);
        }
        return s;
    }

    private static void writeWords(final File f, final ArrayList<String> list) throws IOException {
        final Set<String> s = sortUnique(list);
        final PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(f)));
        for ( final String t : s ) {
            w.println(t);
        }
        w.close();
    }

    private static ArrayList<String> loadDeReWo(final File file, final boolean toLowerCase) {
        final ArrayList<String> list = new ArrayList<String>();

        // get the zip file entry from the file
        InputStream derewoTxtEntry;
        try {
            final ZipFile zip = new ZipFile(file);
            /*
            final Enumeration<? extends ZipEntry> i = zip.entries();
            while (i.hasMoreElements()) {
                final ZipEntry e = i.nextElement();
                System.out.println("loadDeReWo: " + e.getName());
            }
            */
            derewoTxtEntry = zip.getInputStream(zip.getEntry("derewo-v-100000t-2009-04-30-0.1"));
        } catch ( final ZipException e ) {
            Log.logException(e);
            return list;
        } catch ( final IOException e ) {
            Log.logException(e);
            return list;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(derewoTxtEntry, "UTF-8"));
            String line;

            // read until text starts
            while ( (line = reader.readLine()) != null ) {
                if ( line.startsWith("# -----") ) {
                    break;
                }
            }
            // read empty line
            line = reader.readLine();

            // read lines
            int p;
            //int c;
            String w;
            while ( (line = reader.readLine()) != null ) {
                line = line.trim();
                p = line.indexOf(' ', 0);
                if ( p > 0 ) {
                    //c = Integer.parseInt(line.substring(p + 1));
                    //if (c < 1) continue;
                    w =
                        (toLowerCase) ? line.substring(0, p).trim().toLowerCase() : line
                            .substring(0, p)
                            .trim();
                    if ( w.length() < 4 ) {
                        continue;
                    }
                    list.add(w);
                }
            }
            reader.close();
        } catch ( final IOException e ) {
            Log.logException(e);
        } finally {
            if ( reader != null ) {
                try {
                    reader.close();
                } catch ( final Exception e ) {
                }
            }
        }
        return list;
    }

    public static void main(final String[] args) {
        final File here = new File("dummy").getParentFile();
        initialize(new File(here, "DATA/DICTIONARIES"));
        System.out.println("dymDict-size = " + dymLib.size());
        final Set<StringBuilder> r = dymLib.recommend(new StringBuilder("da"));
        for ( final StringBuilder s : r ) {
            System.out.println("$ " + s);
        }
        System.out.println("recommendations: " + r.size());
    }
}
