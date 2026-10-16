# Boulder: OsmAnd schreibt seine Parcelables mit dem Klassennamen in die Bundles, die es
# zurueckgibt (AppInfoParams traegt ALatLon). Der Parcel-Leser sucht die Klasse unter diesem
# Namen; benennt R8 sie um, scheitert getAppInfo, und Kieselstrasse bleibt ohne Hinweise.
# androidApp prueft das am Mapping des Release-Baus (PruefeOsmandNamen).
-keep class net.osmand.aidlapi.** { *; }
