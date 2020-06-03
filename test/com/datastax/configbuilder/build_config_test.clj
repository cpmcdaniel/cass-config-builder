;; Copyright DataStax, Inc.
;; Please see the included license file for details.

(ns com.datastax.configbuilder.build-config-test
  (:require [clojure.test :refer :all]
            [com.datastax.configbuilder.test-data :as test-data]
            [com.datastax.configbuilder.test-helpers :as helper]
            [com.datastax.configbuilder.build-config :as bc]
            [com.datastax.configbuilder.definitions :as d]
            [slingshot.test :refer :all]))

(deftest test-with-defaults
  (let [configs
        (bc/with-defaults (test-data/get-definitions-data helper/default-dse-version)
                          {})]
    ;; Check the total number of config files
    (is (= 22 (count configs)))
    ;; Check some random default values
    (is (= "/var/lib/cassandra/commitlog"
           (get-in configs [:cassandra-yaml :commitlog_directory])))
    (is (= 128 (get-in configs [:cassandra-yaml :io_global_queue_depth])))))

(deftest test-allow-custom-seed-provider
  (let [datacenter-info {:name "dc-1"}
        node-info {:name                               "node-1"
                   :rack                               "rack-1"
                   :listen_address                     "1.1.1.1"
                   :broadcast_address                  "1.1.1.2"
                   :native_transport_address           "1.1.1.3"
                   :native_transport_broadcast_address "1.1.1.4"
                   :initial_token                      "123XYZ"
                   :auto_bootstrap                     true}
        cluster-info {:name  "test-cluster-1"}
        definitions-data (test-data/get-definitions-data "cassandra" helper/default-cassandra-version)
        built-configs (bc/build-configs definitions-data
                                        {:cluster-info    (assoc cluster-info :product "cassandra" :datastax-version helper/default-cassandra-version)
                                         :node-info       node-info
                                         :datacenter-info datacenter-info
                                         :cassandra-yaml  {:seed_provider [{:class_name "org.apache.cassandra.locator.K8SeedProvider"
                                                                            :parameters [{:seeds "1,2,3"}]}]}})]
    (is (= "org.apache.cassandra.locator.K8SeedProvider" (get-in built-configs [:cassandra-yaml :seed_provider 0 :class_name])))
    (is (= "1,2,3" (get-in built-configs [:cassandra-yaml :seed_provider 0 :parameters 0 :seeds])))))

(deftest test-build-configs-for-oss-cassandra
  (let [datacenter-info {:name "dc-1"}
        node-info {:name                               "node-1"
                   :rack                               "rack-1"
                   :listen_address                     "1.1.1.1"
                   :broadcast_address                  "1.1.1.2"
                   :native_transport_address           "1.1.1.3"
                   :native_transport_broadcast_address "1.1.1.4"
                   :initial_token                      "123XYZ"
                   :auto_bootstrap                     true}
        cluster-info {:name  "test-cluster-1"
                      :seeds "1,2,3"}
        definitions-data (test-data/get-definitions-data "cassandra" helper/default-cassandra-version)
        built-configs (bc/build-configs definitions-data
                                        {:cluster-info    (assoc cluster-info :product "cassandra" :datastax-version helper/default-cassandra-version)
                                         :node-info       node-info
                                         :datacenter-info datacenter-info
                                         :cassandra-yaml  {:num_tokens 201}})]

    (testing "does anything at all work even a little bit"
      (is (= 201 (get-in built-configs [:cassandra-yaml :num_tokens]))))
    (testing "do we use rpc_address instead of DSE's new config name native_transport_address"
      (is (= "1.1.1.3" (get-in built-configs [:cassandra-yaml :rpc_address]))))))

(deftest test-ensure-correct-address-field-names
  (let [expected-old {:rpc_address           "1.1.1.1"
                      :broadcast_rpc_address "2.2.2.2"
                      :some_other_prop       "some value"}
        expected-new {:native_transport_address           "1.1.1.1"
                      :native_transport_broadcast_address "2.2.2.2"
                      :some_other_prop                    "some value"}
        fields {:native_transport_address           "1.1.1.1"
                :native_transport_broadcast_address "2.2.2.2"
                :some_other_prop                    "some value"}]
    (testing "DSE 6.0.0+ uses new names"

      (is (= (bc/ensure-correct-address-field-names "dse" "6.0.0" fields)
             expected-new)))
    (testing "DSE 5.0.0 uses old names"
      (is (= (bc/ensure-correct-address-field-names "dse" "5.0.0" fields)
             expected-old)))
    (testing "Cassandra uses old names"
      (is (= (bc/ensure-correct-address-field-names "cassandra" "7.0.0" fields)
             expected-old)))))

(deftest test-build-configs
  (testing "for package installs"
    (let [datacenter-info {:name "dc-1"
                           :graph-enabled 1
                           :spark-enabled 0
                           :solr-enabled  0}
          node-info {:name                               "node-1"
                     :rack                               "rack-1"
                     :listen_address                     "1.1.1.1"
                     :broadcast_address                  "1.1.1.2"
                     :native_transport_address           "1.1.1.3"
                     :native_transport_broadcast_address "1.1.1.4"
                     :initial_token                      "123XYZ"
                     :auto_bootstrap                     true}
          cluster-info {:name  "test-cluster-1"
                        :seeds "1,2,3"}
          built-configs
          (bc/build-configs (test-data/get-definitions-data helper/default-dse-version)
                            {:cluster-info (assoc cluster-info :datastax-version helper/default-dse-version)
                             :node-info    node-info
                             :datacenter-info datacenter-info
                             :dse-env-sh {:dse-log-root "/foo/log"
                                          :cassandra-log-dir "/foo/log/cassandra"
                                          :custom-env-vars {"DSE_SOLR_MODE" "1", "SOLR_ENABLED" "1"}}})]
      (testing "- cassandra.yaml"
        (testing "default values"
          (is (= 128 (get-in built-configs [:cassandra-yaml :io_global_queue_depth]))))
        (testing "ignored fields"
          (is (nil? (get-in built-configs [:cassandra-yaml :rack]))))
        (testing "enriched fields"
          (doseq [[field-name field-val] (dissoc node-info :name :rack)]
            (is (= field-val (get-in built-configs [:cassandra-yaml field-name]))
                (format "Missing or incorrect value for field %s" field-name)))
          (is (= "test-cluster-1" (get-in built-configs [:cassandra-yaml :cluster_name])))
          (is (= "1,2,3" (get-in built-configs [:cassandra-yaml :seed_provider 0 :parameters 0 :seeds])))
          (is (= "1.1.1.3" (get-in built-configs [:cassandra-yaml :native_transport_address])))
          (is (= "1.1.1.4" (get-in built-configs [:cassandra-yaml :native_transport_broadcast_address])))
          (is (every? nil? (map (:cassandra-yaml built-configs) [:rpc_address :broadcast_rpc_address])))))
      (testing "- cassandra-env.sh"
        (is (true? (get-in built-configs [:cassandra-env-sh :enable_on_out_of_memory_error])))
        (is (= "kill -9 %p" (get-in built-configs [:cassandra-env-sh :on_out_of_memory_error])))
        (is (= 7199
               (get-in built-configs [:jvm-options :jmx-port])  ;; source
               (get-in built-configs [:cassandra-env-sh :jmx-port]))))
      (testing "- dse.default"
        (is (= (select-keys datacenter-info bc/workload-keys)
               (select-keys (:dse-default built-configs) bc/workload-keys)))
        (is (= {:cassandra-user "cassandra"
                :cassandra-group "cassandra"}
               (select-keys (get built-configs :dse-default)
                            [:cassandra-user
                             :cassandra-group]))))
      (testing "- cassandra-rackdc.properties"
        (is (= {:dc "dc-1" :rack "rack-1"}
               (:cassandra-rackdc-properties built-configs))))
      (testing "- dse-env.sh"
        (is (= "1" (get-in built-configs [:dse-env-sh :custom-env-vars "SOLR_ENABLED"])))
        (is (= "/foo/log/cassandra" (get-in built-configs [:dse-env-sh :cassandra-log-dir]))))

      (testing "Dependent fields should not be present unless their condition is satisfied"
        (is (false? (get-in built-configs [:dse-yaml :spark_cluster_info_options :enabled])))
        (is (= :missing (get-in built-configs
                                [:dse-yaml :spark_cluster_info_options :refresh_rate_ms]
                                :missing)))
        ;; Try a nested dict with a dependency. Use not instead of false? because this
        ;; enabled field is a ternary_boolean with nil default value.
        (is (not (get-in built-configs [:dse-yaml :dsefs_options :enabled])))
        (is (= :missing (get-in built-configs
                                [:dse-yaml :dsefs_options :gossip_options]
                                :missing))))))

  (testing "for tarball installs"
    (let [built-configs
          (bc/build-configs (test-data/get-definitions-data helper/default-dse-version)
                            {:install-options {:install-type "tarball"
                                               :install-directory "/home/targ/dse"}
                             :dse-env-sh {:dse-log-root "log"
                                          :cassandra-log-dir "log/cassandra"}})]
      (testing "- dse.yaml"
        (is (= "/home/targ/dse/resources/dse/conf"
               (get-in built-configs [:dse-yaml :system_key_directory]))))
      (testing "- dse-env.sh"
        (is (= "/home/targ/dse/log/cassandra" (get-in built-configs [:dse-env-sh :cassandra-log-dir])))))))

(deftest test-unmanage-config-file
  (let [built-configs
        (bc/build-configs (test-data/get-definitions-data helper/default-dse-version)
                          ;; we should be able to unmanage a file based on config-file-id
                          {:cassandra-yaml {:lcm-manage--cassandra-yaml false}})]
    (is (= true (contains? built-configs :dse-yaml)))
    (is (= false (contains? built-configs :cassandra-yaml)))))

(deftest test-build-configs-no-enrichment
  (testing "configs with no enrichment"
    (let [config-data {:cluster-info    {:name             "test-cluster-1"
                                         :datastax-version helper/default-dse-version
                                         :seeds            "1,2,3"}
                       :datacenter-info {:name          "test-dc-1"
                                         :graph-enabled true
                                         :spark-enabled false
                                         :solr-enabled  false}
                       :node-info       {:name                               "node-1"
                                         :rack                               "rack-1"
                                         :listen_address                     "1.1.1.1"
                                         :broadcast_address                  "1.1.1.2"
                                         :native_transport_address           "1.1.1.3"
                                         :native_transport_broadcast_address "1.1.1.4"
                                         :initial_token                      "123XYZ"
                                         :auto_bootstrap                     true}}
          enriched-keys #{:cassandra-yaml
                          :cassandra-env-sh
                          :dse-default
                          :cassandra-rackdc-properties
                          :dse-in-sh}
          definitions-data (test-data/get-definitions-data helper/default-dse-version)
          config-data-with-defaults (bc/with-defaults definitions-data config-data)
          enriched-config-data (bc/build-configs definitions-data config-data)
          unmodified-configs (apply dissoc enriched-config-data
                                    (concat enriched-keys bc/model-info-keys))]
      (doseq [[config-key config-value] unmodified-configs]
        ;; If this fails, an enriched config may have been added. If this is the
        ;; case, add it's config-key to enriched-keys above.
        (is (= (get config-data-with-defaults config-key) config-value)
            (str "Expected config to be unmodified, but it has been enriched: " config-key)))
      ;; If this fails and the actual count is...
      ;; a) Greater than expected - a new config-key has likely been added to the config-data map, and
      ;;    that key is not being enriched. Either it should be enriched, or the expected count
      ;;    should be incremented.
      ;; b) Less than expected - a key that used to be unmodified has either been removed or is
      ;;    now an enriched config. In the former case, decrement the expected count. For the
      ;;    latter, add it's config-key to the enriched-keys set above.
      (is (= 17 (count unmodified-configs))))))

(deftest test-build-configs-bad-keys
  ;; What happens when a key exists in config-data for which there is no corresponding key
  ;; in definitions? The answer - an exception is thrown!
  (let [config-data {:cluster-info    {:name             "test-cluster-1"
                                       :datastax-version helper/default-dse-version
                                       :seeds            "1,2,3"}
                     :datacenter-info {:name          "test-dc-1"
                                       :graph-enabled true
                                       :spark-enabled false
                                       :solr-enabled  false}
                     :node-info       {:name                               "node-1"
                                       :rack                               "rack-1"
                                       :listen_address                     "1.1.1.1"
                                       :broadcast_address                  "1.1.1.2"
                                       :native_transport_address           "1.1.1.3"
                                       :native_transport_broadcast_address "1.1.1.4"
                                       :initial_token                      "123XYZ"
                                       :auto_bootstrap                     true}
                     :bad-config {:a 12}}
        definitions-data (test-data/get-definitions-data helper/default-dse-version)]
    (is (thrown+? [:type :InvalidConfigKeys]
                  (bc/build-configs definitions-data config-data)))))

(deftest test-build-configs-file-paths
  (let [built-configs (bc/build-configs (test-data/get-definitions-data helper/default-dse-version)
                                        {:cassandra-yaml {}
                                         :address-yaml   {}})]
    (is (= "/etc/dse/cassandra/cassandra.yaml"
           (get-in built-configs [:node-info :file-paths :cassandra-yaml])))
    (is (= (:package-path bc/address-yaml-paths)
           (get-in built-configs [:node-info :file-paths :address-yaml])))))

(deftest test-get-configured-paths
  (let [configured-paths
        (get-in
         (bc/get-configured-paths
          {:definitions
           {:foobie
            {:display-name "foobie.yaml"
             :properties
             {:blah_dirs {:type          "list"
                          :value_type    "string"
                          :default_value ["/blah/default"]
                          :is_directory  true}
              :foo_dir   {:type          "string"
                          :default_value "/foo/default"
                          :is_file  true}
              :bar_dir   {:type         "string"
                          :is_directory true}}}}}
          :foobie
          {:foobie {:blah_dirs ["/a/b/c" "/d/e/f"]
                    :foo_dir   "/foo/default"
                    :bar_dir   "/j/k/l"}})
         [:node-info :configured-paths])]
    (is (= 4 (count configured-paths)))
    (doseq [configured-path configured-paths]
      (is (= "foobie.yaml" (:config-file configured-path)))
      (condp = (:key configured-path)
        [:blah_dirs]
        (do
          (is (#{"/a/b/c" "/d/e/f"} (:path configured-path)))
          (is (:custom? configured-path))
          (is (:directory? configured-path)))

        [:foo_dir]
        (do
          (is (= "/foo/default" (:path configured-path)))
          (is (false? (:custom? configured-path)))
          (is (false? (:directory? configured-path))))

        [:bar_dir]
        (do
          (is (= "/j/k/l" (:path configured-path)))
          (is (:custom? configured-path))
          (is (:directory? configured-path)))

        (do
          (is (= "Unexpected value" configured-path))))))
  (testing "for tarball installs"
    (let [definitions-data (update (test-data/get-definitions-data helper/default-dse-version)
                                   :definitions
                                   d/use-tarball-defaults)
          [configured-path]
          (get-in
           (bc/get-configured-paths definitions-data
                                    :dse-yaml
                                    {:dse-yaml {:system_key_directory "resources/dse/conf"}})
           [:node-info :configured-paths])]
      (is configured-path)
      (is (= "resources/dse/conf" (:path configured-path)))
      (is (false? (:custom? configured-path))))))

(deftest test-fully-qualify-paths
  (let [definitions-data (test-data/get-definitions-data)
        config-key :cassandra-yaml]
    (testing "No-op for package installs"
      (let [config-data {:install-options
                         {:install-type "package"}
                         :cassandra-yaml
                         {:data_file_directories ["/var/data1" "/var/data2"]
                          :commitlog_directory "/var/commitlog"
                          :client_encryption_options
                          {:enabled true
                           :keystore "/etc/dse/keystore"}}}
            result (bc/fully-qualify-paths definitions-data config-key config-data)]
        (is (= result config-data)
            "Should not modify paths for package install")))
    (testing "Tarball installs"
      (let [config-data {:install-options
                         {:install-type "tarball"
                          :install-directory "/opt/dse"}
                         :cassandra-yaml
                         {:data_file_directories ["var/data1" "var/data2" "/var/data3"]
                          :commitlog_directory "var/commitlog"
                          :client_encryption_options
                          {:enabled true
                           :keystore "etc/dse/keystore"
                           :truststore "/etc/dse/truststore"}}}
            result (bc/fully-qualify-paths definitions-data config-key config-data)]
        (is (= ["/opt/dse/var/data1"
                "/opt/dse/var/data2"
                "/var/data3"]
               (get-in result [:cassandra-yaml :data_file_directories]))
            "Should fully-qualify relative paths in vectors")
        (is (= "/opt/dse/var/commitlog"
               (get-in result [:cassandra-yaml :commitlog_directory]))
            "Should fully-qualify relative directory paths")
        (is (= "/opt/dse/etc/dse/keystore"
               (get-in result [:cassandra-yaml :client_encryption_options :keystore]))
            "Should fully-qualify relative file paths")
        (is (= "/etc/dse/truststore"
               (get-in result [:cassandra-yaml :client_encryption_options :truststore]))
            "Should not transform paths that are already absolute")))))

(deftest test-get-install-directory
  (testing "Non-empty install-directory"
    (is (= "/a/b/c"
           (bc/get-install-directory {:install-options
                                      {:install-directory "/a/b/c"}}))))
  (testing "Empty install-directory"
    (is (= "/d/e/f"
           (bc/get-install-directory {:install-options
                                      {:install-directory ""}
                                      :node-info
                                      {:facts
                                       {:run-context
                                        {:install-options
                                         {:install-directory "/d/e/f"}}}}})))))

