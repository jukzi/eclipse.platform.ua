/*******************************************************************************
 * Copyright (c) 2015 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ua.tests.doc.internal.linkchecker;

import java.net.URI;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.RegistryFactory;
import org.eclipse.equinox.frameworkadmin.BundleInfo;
import org.eclipse.equinox.simpleconfigurator.manipulator.SimpleConfiguratorManipulator;
import org.eclipse.help.IHelpResource;
import org.eclipse.help.IToc;
import org.eclipse.help.IUAElement;
import org.eclipse.help.internal.toc.TocContribution;
import org.eclipse.help.internal.toc.TocFile;
import org.eclipse.help.internal.toc.TocFileParser;
import org.eclipse.osgi.service.resolver.ExportPackageDescription;
import org.eclipse.osgi.service.resolver.PlatformAdmin;
import org.eclipse.osgi.service.resolver.State;
import org.eclipse.ua.tests.doc.internal.Activator;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import junit.framework.TestCase;

public class ApiDocTest extends TestCase {
	
	static class InternalExtensionFoundException extends SAXException {
		private static final long serialVersionUID = 1L;
	}
	
	static class InternalExtensionFinder extends DefaultHandler {
		/* Look for this pattern:
		   <element name="extension">
		      <annotation>
		         <appInfo>
		            <meta.element internal="true" />
		 */
		
		int state = 0;
		
		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			switch (state) {
			case 0:
				if ("element".equalsIgnoreCase(qName) && "extension".equals(attributes.getValue("name")))
					state = 1;
				break;
				
			case 1:
				if ("annotation".equalsIgnoreCase(qName))
					state = 2;
				break;
				
			case 2:
				if ("appInfo".equalsIgnoreCase(qName))
					state = 3;
				break;
				
			case 3:
				if ("meta.element".equalsIgnoreCase(qName) && "true".equals(attributes.getValue("internal"))) {
					throw new InternalExtensionFoundException();
				}
				break;
			}
		}
		
		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			state = 0;
		}
	}
	
	private static final String[] TOCS = {
			"org.eclipse.platform.doc.isv",
			"org.eclipse.jdt.doc.isv",
			"org.eclipse.pde.doc.user",
	};
	private static final String TOPICS_REFERENCE_XML = "topics_Reference.xml";
	private static final String REFERENCE_EXTENSION_POINTS = "reference/extension-points/";
	private static final String REFERENCE_API = "reference/api/";
	private static final Pattern NON_API_PACKAGES = Pattern.compile(".+\\.(?:internal|tests|examples)(?:\\..+)?");
	
	/**
	 * Missing extension points / API packages currently don't make this test fail,
	 * since the list contains false positives (esp. when run locally). The test doesn't
	 * know which bundles eventually end up in the build, and which bundles are:
	 * <ul>
	 * <li>part of the test infrastructure</li>
	 * <li>dependencies (e.g. from Orbit)</li>
	 * <li>unrelated projects in the user's workspace that are not excluded in the launch configuration</li>
	 * </ul>
	 * 
	 * However, the test prints potentially missing extension points / API packages to System.out.
	 * 
	 * @throws Exception 
	 */
	public void testTopicsReference() throws Exception {
		System.out.println("Running " + ApiDocTest.class.getName() + "#testTopicsReference()\n");
		
		StringBuilder problems = new StringBuilder();
		
		Set<String> extIds = new TreeSet<String>();
		Set<String> packageIds = new TreeSet<String>();
		
		TocFileParser parser = new TocFileParser();
		for (String tocFile : TOCS) {
			TocContribution contribution = parser.parse(new TocFile(tocFile, TOPICS_REFERENCE_XML, true, "en", null, null));
			IToc toc = contribution.getToc();
			IUAElement[] children = toc.getChildren();
			for (IUAElement child : children) {
				for (IUAElement child2 : child.getChildren()) {
					if (child2 instanceof IHelpResource) {
						IHelpResource topic = (IHelpResource) child2;
						String href = topic.getHref();
						if (href != null) {
							if (href.startsWith(REFERENCE_EXTENSION_POINTS)) {
								String id = topic.getLabel();
								if (!extIds.add(id) ) {
									problems.append("Extension point label '" + id + "' appears more than once in '" + TOPICS_REFERENCE_XML + "' files.\n");
									continue;
								}
								String filename = href.substring(REFERENCE_EXTENSION_POINTS.length());
								String expectedFileName = id.replace('.', '_') + ".html";
								if (!expectedFileName.equals(filename)) {
									problems.append("File name for extension point '" + id + "' expected: '" + expectedFileName + "' but was: '" + filename + "'\n");
								}
							} else if (href.startsWith(REFERENCE_API)) {
								String id = topic.getLabel();
								if (!packageIds.add(id) ) {
									problems.append("API package label '" + id + "' appears more than once in '" + TOPICS_REFERENCE_XML + "' files.\n");
									continue;
								}
								String filename = href.substring(REFERENCE_API.length());
								String expectedFileName = id.replace('.', '/') + "/package-summary.html";
								if (!expectedFileName.equals(filename)) {
									problems.append("File name for package label '" + id + "' expected: '" + expectedFileName + "' but was: '" + filename + "'\n");
								}
							}
						}
					}
				}
			}
		}
		
		checkExtensionPoints(extIds, problems);
		checkPackages(packageIds, problems);
		
		assertEquals("", problems.toString());
	}

	private static void checkExtensionPoints(Set<String> extIds, StringBuilder problems) throws Exception {
		Callable<BundleInfo[]> sourceBundlesCache= new Callable<BundleInfo[]>() {
			private BundleInfo[] bundleInfos;
			
			@Override
			public BundleInfo[] call() throws Exception {
				if (bundleInfos == null) {
					BundleContext context = Activator.getDefault().getBundle().getBundleContext();
					ServiceReference<SimpleConfiguratorManipulator> serviceReference = context.getServiceReference(SimpleConfiguratorManipulator.class);
					SimpleConfiguratorManipulator manipulator = context.getService(serviceReference);
					bundleInfos = manipulator.loadConfiguration(context, SimpleConfiguratorManipulator.SOURCE_INFO);
				}
				return bundleInfos;
			}
		};
		
		Set<String> registeredIds = new TreeSet<String>();
		
		IExtensionRegistry registry = RegistryFactory.getRegistry();
		IExtensionPoint[] extensionPoints = registry.getExtensionPoints();
		for (IExtensionPoint extensionPoint : extensionPoints) {
			String id = extensionPoint.getUniqueIdentifier();
			String schemaReference = extensionPoint.getSchemaReference();
			if (schemaReference == null || schemaReference.isEmpty()) {
				problems.append("Extension point missing a schema reference: " + id + "\n");
			} else {
				InputSource schemaSource = getExtensionPointSchemaSource(extensionPoint, schemaReference, sourceBundlesCache);
				if (schemaSource != null) {
					SAXParserFactory parserFactory = SAXParserFactory.newInstance();
					SAXParser parser = parserFactory.newSAXParser();
					InternalExtensionFinder handler = new InternalExtensionFinder();
					try {
						parser.parse(schemaSource, handler);
					} catch (InternalExtensionFoundException e) {
						System.out.append("Skipping internal extension point " + id + "\n");
						continue; // don't report internal extension points as undocumented
					}
				} else {
					System.out.append("Extension point schema file not found for " + id + ": " + schemaReference + "\n");
				}
			}
			registeredIds.add(id);
		}
		
		TreeSet<String> unexpectedTocIds = new TreeSet<String>(extIds);
		unexpectedTocIds.removeAll(registeredIds);
		if (!unexpectedTocIds.isEmpty()) {
			problems.append("\n* Unexpected extension points in " + TOPICS_REFERENCE_XML + ":\n");
			for (String unexpectedTocId : unexpectedTocIds) {
				problems.append(unexpectedTocId).append('\n');
			}
		}
		
		registeredIds.removeAll(extIds);
		if (!registeredIds.isEmpty()) {
			// these currently don't make the test fail, since the list contains false positives (esp. when run locally)
			System.out.append("\n* Undocumented non-internal extension points:\n");
			for (String registeredId : registeredIds) {
				System.out.append(registeredId).append('\n');
			}
		}
	}

	private static InputSource getExtensionPointSchemaSource(IExtensionPoint extensionPoint, String schemaReference, Callable<BundleInfo[]> sourceBundlesLoader) throws Exception {
		String contributor = extensionPoint.getContributor().getName();
		Bundle bundle = Platform.getBundle(contributor);
		URL schemaURL = bundle.getEntry(schemaReference);
		if (schemaURL != null) {
			return new InputSource(schemaURL.toString());
		} else {
			// Binary bundles typically miss the schema/*.exsd files.
			// Let's try to find it in the source bundle (e.g. during the official test run):
			BundleInfo[] bundles = sourceBundlesLoader.call();
			for (BundleInfo bundleInfo : bundles) {
				if (bundleInfo.getSymbolicName().equals(contributor + ".source")) {
					URI location = bundleInfo.getLocation();
					URL fileURL = FileLocator.toFileURL(location.toURL());
					ZipFile zipFile = new ZipFile(fileURL.getPath());
					ZipEntry entry = zipFile.getEntry(schemaReference);
					if (entry == null) {
						return null;
					}
					return new InputSource(zipFile.getInputStream(entry));
				}
			}
			return null;
		}
	}
	
	private static void checkPackages(Set<String> packageIds, StringBuilder problems) {
		Set<String> exportedPackageIds = new TreeSet<String>();
		
		exportedPackageIds.add("org.eclipse.core.runtime.adaptor"); // not exported, but makes sense to document since accessible from outside of OSGi framework
		exportedPackageIds.add("org.eclipse.swt.ole.win32"); // somehow missing from State#getExportedPackages(), maybe because it's declared in the fragment only

		BundleContext context = Activator.getDefault().getBundle().getBundleContext();
		ServiceReference<PlatformAdmin> platformAdminReference = context.getServiceReference(PlatformAdmin.class);
		PlatformAdmin service = context.getService(platformAdminReference);
		State state = service.getState(false);
		ExportPackageDescription[] exportedPackages = state.getExportedPackages();
		for (ExportPackageDescription exportPackageDescription : exportedPackages) {
			String name = exportPackageDescription.getName();
			if (!NON_API_PACKAGES.matcher(name).matches()) {
				if (Boolean.TRUE.equals(exportPackageDescription.getDirective("x-internal"))
						|| exportPackageDescription.getDirective("x-friends") != null) {
					continue;
				}
				exportedPackageIds.add(name);
//				Enumeration<String> packageChildren = exportPackageDescription.getSupplier().getBundle().getEntryPaths(name.replace('.', '/'));
//				while (packageChildren != null && packageChildren.hasMoreElements()) {
//					String child = packageChildren.nextElement();
//					if (child.endsWith(".class")) {
//						exportedPackageIds.add(name);
//						break pack;
//					}
//				}
//				System.out.append("Exported package without class files: ").append(name).append('\n');
			}
		}
		
		TreeSet<String> unexpectedPackageIds = new TreeSet<String>(packageIds);
		unexpectedPackageIds.removeAll(exportedPackageIds);
		if (!unexpectedPackageIds.isEmpty()) {
			problems.append("\n* Unexpected exported API packages in " + TOPICS_REFERENCE_XML + ":\n");
			for (String unexpectedTocId : unexpectedPackageIds) {
				problems.append(unexpectedTocId).append('\n');
			}
		}
		
		exportedPackageIds.removeAll(packageIds);
		if (!exportedPackageIds.isEmpty()) {
			// these currently don't make the test fail, since the list contains false positives (esp. when run locally)
			System.out.append("\n* Undocumented exported API package:\n");
			for (String exportedPackageId : exportedPackageIds) {
				System.out.append(exportedPackageId).append('\n');
			}
		}
	}

}
