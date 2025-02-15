package org.inventivetalent.trashapp;

import android.Manifest;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProviders;
import androidx.preference.PreferenceManager;
import androidx.room.Room;
import com.android.billingclient.api.*;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.location.*;
import com.google.android.material.tabs.TabLayout;
import org.inventivetalent.trashapp.common.*;
import org.inventivetalent.trashapp.common.db.AppDatabase;
import org.inventivetalent.trashapp.ui.main.PageViewModel;
import org.inventivetalent.trashapp.ui.main.SectionsPagerAdapter;

import java.util.*;

import static org.inventivetalent.trashapp.common.Constants.*;
import static org.inventivetalent.trashapp.common.OverpassResponse.elementsSortedByDistanceFrom;

public class TabActivity extends AppCompatActivity implements TrashCanResultHandler, TrashcanUpdater, PaymentHandler, BillingManager.BillingUpdatesListener {

	protected static TabActivity instance;

	private SharedPreferences sharedPreferences;
	private boolean           debug;

	private       LocationManager  mLocationManager;
	public static Location         lastKnownLocation;
	public static GeomagneticField geoField;

	private       SensorManager mSensorManager;
	private       float[]       mGravity          = new float[3];
	private       boolean       gravitySet;
	private       float[]       mGeomagnetic      = new float[3];
	private       boolean       magneticSet;
	public static float[]       lastKnownRotation = new float[3];

	public static RotationBuffer rotationBuffer = new RotationBuffer();

	boolean initialSearchCompleted = false;
	public static List<LatLon> nearbyTrashCans = new ArrayList<>();
	public static LatLon       closestTrashCan;

	private BillingManager            billingManager;
	private boolean                   billingManagerReady;
	private Set<String>               purchasedSkus         = new HashSet<>();
	private Set<PaymentReadyListener> paymentReadyListeners = new HashSet<>();

	protected static SkuInfo SKU_INFO_PREMIUM;

	private   int         searchItaration = 0;
	protected AppDatabase appDatabase;

	private       FusedLocationProviderClient fusedLocationProviderClient;
	private       LocationRequest             locationRequest   = new LocationRequest()
			.setInterval(Constants.LOCATION_INTERVAL)
			.setFastestInterval(Constants.LOCATION_INTERVAL_MIN)
			.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
	private final LocationListener            mLocationListener = new LocationListener() {
		@Override
		public void onLocationChanged(final Location location) {
			Log.i("TrashApp", "onLocationChanged");
			Log.i("TrashApp", location.toString());

			setLastKnownLocation(location);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
		}

		@Override
		public void onProviderEnabled(String provider) {
		}

		@Override
		public void onProviderDisabled(String provider) {
		}
	};
	private final LocationCallback            locationCallback  = new LocationCallback() {
		@Override
		public void onLocationResult(LocationResult locationResult) {
			if (locationResult == null) {
				return;
			}

			Log.i("TrashApp", "onLocationResult");
			Log.i("TrashApp", locationResult.toString());

			setLastKnownLocation(locationResult.getLastLocation());
		}
	};
	private final SensorEventListener         mSensorListener   = new SensorEventListener() {
		@Override
		public void onSensorChanged(SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
				System.arraycopy(event.values, 0, mGravity, 0, event.values.length);
				gravitySet = true;
			}
			if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
				System.arraycopy(event.values, 0, mGeomagnetic, 0, event.values.length);
				magneticSet = true;
			}

			if (gravitySet && magneticSet) {
				float[] r = new float[9];
				float[] i = new float[9];

				if (SensorManager.getRotationMatrix(r, i, mGravity, mGeomagnetic)) {
					SensorManager.getOrientation(r, lastKnownRotation);
					rotationBuffer.add(lastKnownRotation[0]);

					ViewModelProviders.of(TabActivity.this).get(PageViewModel.class).mRotation.setValue(lastKnownRotation[0]);
				}
			}
		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		instance = this;

		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
			System.out.println(entry.getKey() + ": " + entry.getValue() + " (" + entry.getValue().getClass() + ")");
		}
		debug = Util.getBoolean(sharedPreferences, "enable_debug", false);

		Util.applyTheme(this, sharedPreferences);

		setContentView(R.layout.activity_tab);
		SectionsPagerAdapter sectionsPagerAdapter = new SectionsPagerAdapter(this, getSupportFragmentManager());
		CustomViewPager viewPager = findViewById(R.id.view_pager);
		viewPager.setAdapter(sectionsPagerAdapter);
		TabLayout tabs = findViewById(R.id.tabs);
		tabs.setupWithViewPager(viewPager);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.hide();
		}

		Intent intent = getIntent();
		if (intent != null) {
			Log.i("TrashApp", intent.toString());
			Log.i("TrashApp", intent.getAction());
			Uri data = intent.getData();
			Log.i("TrashApp", data != null ? data.toString() : "n/a");
		}

		billingManager = new BillingManager(this, this);

		MobileAds.initialize(this, "ca-app-pub-2604356629473365~4556622372");

		appDatabase = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "trashapp").build();

		//		FloatingActionButton fab = findViewById(R.id.fab);
		//
		//		fab.setOnClickListener(new View.OnClickListener() {
		//			@Override
		//			public void onClick(View view) {
		//				Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
		//						.setAction("Action", null).show();
		//			}
		//		});
		Util.showDebugDBAddressLogToast(this);
	}

	@Override
	protected void onResume() {
		super.onResume();

		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		if (mSensorManager == null) {
			Toast.makeText(this, "Your device doesn't support sensors", Toast.LENGTH_LONG).show();
			exitApp();
			return;
		}
		Sensor magneticSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mSensorManager.registerListener(mSensorListener, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL);
		mSensorManager.registerListener(mSensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

		mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
		if (requestLocationUpdates(true)) {
			lookForTrashCans();
		}

		if (billingManager != null
				&& billingManager.getBillingClientResponseCode() == BillingClient.BillingResponseCode.OK) {
			billingManager.queryPurchases();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();

		updateWidget();

		if (mSensorManager != null) { mSensorManager.unregisterListener(mSensorListener); }
		//		if (mLocationManager != null) { mLocationManager.removeUpdates(mLocationListener); }
		if (fusedLocationProviderClient != null) { fusedLocationProviderClient.removeLocationUpdates(locationCallback); }
	}

	@Override
	protected void onDestroy() {
		instance = null;
		if (billingManager != null) {
			billingManager.destroy();
		}
		super.onDestroy();
	}

	void setLastKnownLocation(Location location) {
		if (location != null) {
			geoField = new GeomagneticField(
					(float) location.getLatitude(),
					(float) location.getLongitude(),
					(float) location.getAltitude(),
					System.currentTimeMillis()
			);
			lastKnownLocation = location;

			ViewModelProviders.of(this).get(PageViewModel.class).mLocation.setValue(location);

			if (!initialSearchCompleted) {
				lookForTrashCans();
			}
			updateClosestTrashcan(nearbyTrashCans);
		}
	}

	boolean requestLocationUpdates(boolean ask) {
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			Log.i("TrashApp", "Location permissions not granted");
			if (ask) {
				Log.i("TrashApp", "Requesting location permissions");
				ActivityCompat.requestPermissions(this, new String[] {
						Manifest.permission.ACCESS_FINE_LOCATION,
						Manifest.permission.ACCESS_COARSE_LOCATION }, REQUEST_LOCATION_PERMS_CODE);
				return false;
			}
			Log.i("TrashApp", "Location permissions not granted and can't ask - exiting!");

			Toast.makeText(this, "This app requires location permissions", Toast.LENGTH_LONG).show();
			exitApp();
			return false;
		}

		Log.i("TrashApp", "Location permissions granted!");
		// has permission, request!

		fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, null/*Looper*/);

		//		//TODO: use google play services for location updates
		//		mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_REFRESH_TIME,
		//				LOCATION_REFRESH_DISTANCE, mLocationListener);
		//		mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_REFRESH_TIME,
		//				LOCATION_REFRESH_DISTANCE, mLocationListener);

		Log.i("TrashApp", "Trying to get last known location from providers");
		Location location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		if (location == null) {
			location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
		} else {
			Log.i("TrashApp", "got last known location from gps provider");
		}
		if (location == null) {
			location = mLocationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
		} else {
			Log.i("TrashApp", "got last known location from network provider");
		}
		setLastKnownLocation(location);
		Log.i("TrashApp", lastKnownLocation != null ? lastKnownLocation.toString() : "n/a");

		mLocationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, mLocationListener, null);

		return true;
	}

	@Override
	public void lookForTrashCans() {
		if (lastKnownLocation == null) {
			return;
		}
		Log.i("TrashApp", "Looking for trash cans");
		double searchRadius = Util.getInt(sharedPreferences, "search_radius_start", DEFAULT_SEARCH_RADIUS) + SEARCH_STEP * searchItaration;// meters
		//TODO: might need to steadily increase the radius if we can't find anything closer
		double searchRadiusDeg = searchRadius * ONE_METER_DEG;

		Log.i("TrashApp", "Radius: " + (searchRadius / 1000) + "km / " + searchRadius + "m / " + searchRadiusDeg + "deg");

		double lat = lastKnownLocation.getLatitude();
		double lon = lastKnownLocation.getLongitude();

		OverpassBoundingBox boundingBox = new OverpassBoundingBox(lat - searchRadiusDeg, lon - searchRadiusDeg, lat + searchRadiusDeg, lon + searchRadiusDeg);
		Log.i("TrashApp", boundingBox.toCoordString());

		//TODO: make this more efficient, i.e. don't run both
		new DbTrashcanQueryTask(this).execute(boundingBox);
		new TrashCanFinderTask(this, this).execute(boundingBox);
	}

	@Override
	public AppDatabase getDatabase() {
		return appDatabase;
	}

	@Override
	public void handleTrashCanLocations(List<? extends LatLon> elements, boolean isCached) {
		Log.i("TrashApp", "Got trashcan locations (cached: " + isCached + ")");

		initialSearchCompleted = true;

		//		elements = convertElementsToPoints(elements);
		Log.i("TrashApp", elements.toString());

		if (elements.isEmpty()) {
			if (!isCached && Util.getInt(sharedPreferences, "search_radius_start", DEFAULT_SEARCH_RADIUS) + SEARCH_STEP * searchItaration < Util.getInt(sharedPreferences, "search_radius_max", MAX_SEARCH_RADIUS)) {
				// still below max radius, keep looking
				searchItaration++;
				lookForTrashCans();
			} else {
				// reset
				searchItaration = 0;

				if (!isCached) { Toast.makeText(this, R.string.err_no_trashcans, Toast.LENGTH_LONG).show(); }
			}
		} else {
			Util.insertTrashcanResult(appDatabase, elements);
		}
		updateClosestTrashcan(elements);
	}

	public void updateClosestTrashcan(List<? extends LatLon> elements) {
		if (elements.isEmpty()) {
			closestTrashCan = null;
			ViewModelProviders.of(this).get(PageViewModel.class).mClosestCan.setValue(null);
		} else {
			elements = elementsSortedByDistanceFrom(elements, lastKnownLocation);
			// no need to convert to points again
			nearbyTrashCans.clear();
			nearbyTrashCans.addAll(elements);

			int i = 0;
			//			for (OverpassResponse.Element element : elements) {
			//				Log.i("TrashApp", (i++) + " " + element.toLocation() + " => " + lastKnownLocation.distanceTo(element.toLocation()));
			//			}

			LatLon closest = elements.get(0);
			ViewModelProviders.of(this).get(PageViewModel.class).mClosestCan.setValue(closest);
			closestTrashCan = closest;

			// reset
			searchItaration = 0;

			updateWidget();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == REQUEST_LOCATION_PERMS_CODE) {
			// just try to init the updates again
			if (requestLocationUpdates(false)) {
				lookForTrashCans();
			}
		}
	}

	@Override
	public void launchBilling(SkuDetails skuDetails) {
		if (billingManager != null) {
			billingManager.initiatePurchaseFlow(skuDetails);
		}
	}

	@Override
	public boolean isPurchased(String sku) {
		return purchasedSkus.contains(sku);
	}

	@Override
	public void onBillingClientSetupFinished() {
		Log.i("TrashApp", "onBillingClientSetupFinished");

		Log.i("TrashApp", "Querying Sku Details...");
		billingManager.querySkuDetailsAsync(BillingClient.SkuType.INAPP, Arrays.asList(BillingConstants.IN_APP_SKUS), new SkuDetailsResponseListener() {
			@Override
			public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetailsList) {
				Log.i("TrashApp", "onSkuDetailsResponse");
				Log.i("TrashApp", "result: " + billingResult);
				Log.i("TrashApp", "list(" + (skuDetailsList != null ? skuDetailsList.size() : 0) + "): " + skuDetailsList);

				if (skuDetailsList != null && skuDetailsList.size() > 0) {
					for (SkuDetails details : skuDetailsList) {
						switch (details.getSku()) {
							case BillingConstants.SKU_PREMIUM:
								SKU_INFO_PREMIUM = new SkuInfo(details, TabActivity.this);
								break;
							default:
								Log.w("TabActivity", "Unhandled SkuDetails: " + details.getSku());
								break;
						}
					}

					billingManagerReady = true;
					for (PaymentReadyListener listener : paymentReadyListeners) {
						listener.ready();
					}
					paymentReadyListeners.clear();
				}
			}
		});
	}

	@Override
	public void waitForManager(PaymentReadyListener listener) {
		if (billingManagerReady) {
			listener.ready();
			return;
		}
		paymentReadyListeners.add(listener);
	}

	@Override
	public void onConsumeFinished(String token, BillingResult billingResult) {
		Log.i("TrashApp", "onConsumeFinished");
		Log.i("TrashApp", "token: " + token);
		Log.i("TrashApp", "result: " + billingResult);
	}

	@Override
	public void onPurchasesUpdated(List<Purchase> purchases) {
		Log.i("TrashApp", "onPurchasesUpdated");
		Log.i("TrashApp", "purchases(" + purchases.size() + "): " + purchases);

		for (Purchase purchase : purchases) {
			Log.i("TrashApp", purchase.getSku() + ": " + purchase.getPurchaseState());
			purchasedSkus.add(purchase.getSku());
		}
	}

	void updateWidget() {
		if (closestTrashCan == null) { return; }
		if (lastKnownLocation == null) { return; }

		Location canLocation = closestTrashCan.toLocation();
		float bearing = lastKnownLocation.bearingTo(canLocation);
		//		float angle = (float) (bearing - heading)*-1;
		float azimuth = rotationBuffer.getAverageAzimuth();
		//		 azimuth = (float) Math.toDegrees(azimuth);
		if (geoField != null) {
			azimuth += geoField.getDeclination();
		}
		float angle = (float) (azimuth - bearing);
		if (angle < 0) { angle += 360f; }

		int rounded = ((int) Math.round(angle / 45)) * 45;
		Log.i("CompassWidget", "rounded: " + rounded);
		int resId = -1;
		switch (rounded) {
			case 45:
				resId = R.drawable.ic_small_pointer_r45;
				break;
			case 90:
				resId = R.drawable.ic_small_pointer_r90;
				break;
			case 135:
				resId = R.drawable.ic_small_pointer_r135;
				break;
			case 180:
				resId = R.drawable.ic_small_pointer_r180;
				break;
			case 225:
				resId = R.drawable.ic_small_pointer_r225;
				break;
			case 270:
				resId = R.drawable.ic_small_pointer_r270;
				break;
			case 315:
				resId = R.drawable.ic_small_pointer_r315;
				break;
			case 0:
			case 360:
			default:
				resId = R.drawable.ic_small_pointer_r0;
				break;
		}

		CompassWidget.pointerResId = resId;

		Intent intent = new Intent(this, CompassWidget.class);
		intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
		// Use an array and EXTRA_APPWIDGET_IDS instead of AppWidgetManager.EXTRA_APPWIDGET_ID,
		// since it seems the onUpdate() is only fired on that:
		int[] ids = AppWidgetManager.getInstance(getApplication()).getAppWidgetIds(new ComponentName(getApplication(), CompassWidget.class));
		intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
		sendBroadcast(intent);
	}

	void exitApp() {
		Intent homeIntent = new Intent(Intent.ACTION_MAIN);
		homeIntent.addCategory(Intent.CATEGORY_HOME);
		homeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(homeIntent);
	}

}