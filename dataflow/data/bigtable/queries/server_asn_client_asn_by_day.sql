SELECT server_asn_name,
       client_asn_number,
       client_asn_name,
       local_time_zone,
       local_zone_name,
       DATE(local_test_date) AS date,
       SUM(rtt_sum) / SUM(rtt_count) AS rtt_avg,
       AVG(packet_retransmit_rate) AS retransmit_avg,
       nth(51, quantiles(download_speed_mbps, 101)) AS download_speed_mbps_median,
       nth(51, quantiles(upload_speed_mbps, 101)) AS upload_speed_mbps_median,
       COUNT(*) AS count
FROM {0}
WHERE LENGTH(client_asn_name) > 0
 AND LENGTH(local_time_zone) > 0
 AND LENGTH(local_zone_name) > 0
 AND LENGTH(server_asn_name) > 0
 AND LENGTH(client_asn_number) > 0
GROUP BY server_asn_name,
         client_asn_number,
         client_asn_name,
         local_time_zone,
         local_zone_name,
         [date]