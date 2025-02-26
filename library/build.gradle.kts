import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.vanniktech.maven.publish") version "0.27.0"
}

publishing {
    repositories {
        maven {
            name = "TrackingSDK"
            url = uri("https://aws.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials(PasswordCredentials::class)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.DEFAULT, automaticRelease = true)
    signAllPublications()

    coordinates("software.amazon.location", "tracking", "1.0.0")

    pom {
        name.set("Amazon Location Service Mobile Tracking SDK for Android")
        description.set("These utilities help you when making Amazon Location Service API calls from your Android applications. This library uses the AWS SDK to call tracking APIs.")
        inceptionYear.set("2024")
        url.set("https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("aws-geospatial")
                name.set("AWS Geospatial")
                url.set("https://github.com/aws-geospatial")
            }
        }
        scm {
            url.set("https://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android")
            connection.set("scm:git:git://github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android")
            developerConnection.set("scm:git:ssh://git@github.com/aws-geospatial/amazon-location-mobile-tracking-sdk-android")
        }
    }
}


android {
    namespace = "software.amazon.location.tracking"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("aws.sdk.kotlin:location:1.3.65")

    if (findProject(":authSdk") != null) {
        implementation(project(":authSdk"))
    } else {
        implementation("software.amazon.location:auth:1.1.0")
    }

    val roomVersion = "2.6.1"

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("aws.sdk.kotlin:aws-http:1.3.65")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
