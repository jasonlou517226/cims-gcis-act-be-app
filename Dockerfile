# dhci2_auto_check — Playwright (Java) automated tests, containerized.
#
# Build:  docker build -t dhci2-auto-check .
# Run:    docker run --rm \
#           -e OB_LOGIN=... -e OB_PASSWORD=... \
#           dhci2-auto-check            # default target: booking (page check + OCR login)
#   or     docker run --rm dhci2-auto-check portal
#   or     docker run --rm dhci2-auto-check all
#
# Browsers and the Maven repo are baked into the image, so runs are fully
# offline (except the tested websites themselves).
FROM maven:3.9-eclipse-temurin-17

# --- OCR (tesseract) + fonts + Chromium runtime libs + certificates ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        fonts-liberation \
        fonts-dejavu-core \
        tesseract-ocr \
        libxcursor1 \
        libgtk-3-0t64 \
        libcairo-gobject2 \
        libgdk-pixbuf-2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# --- Pre-fetch Maven dependencies (cached layer) ------------------------------
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# --- Source + Playwright Chromium with its system deps ------------------------
COPY src ./src
# --with-deps installs the remaining Chromium runtime libraries via apt.
RUN mvn -B -q exec:java -e \
        -D exec.mainClass=com.microsoft.playwright.CLI \
        -D exec.args="install --with-deps chromium"

# --- Entrypoint -----------------------------------------------------------------
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# Tests are always headless inside the container.
# The remaining "missing" libs (GTK4/GStreamer/flite/...) are optional Chromium
# multimedia features unavailable in Debian trixie — skip host validation.
ENV HEADED=false \
    SLOWMO=false \
    TARGET=booking \
    PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]